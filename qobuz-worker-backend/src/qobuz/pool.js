/**
 * AccountPool & Load Balancer for Qobuz API Clients
 *
 * Implements:
 * - Equal-probability load distribution across all healthy accounts (fair load reduction)
 * - Automatic failure detection & circuit-breaker cooldowns
 * - Zero-downtime automatic failover retry loop
 * - Real-time account health & metrics tracking
 */

import { QobuzClient } from "./client.js";

export class AccountPool {
  constructor(accountConfigs = []) {
    this.clients = [];
    this.stats = new Map();
    this.roundRobinIndex = 0;

    for (let i = 0; i < accountConfigs.length; i++) {
      const config = accountConfigs[i];
      const id = config.id || `account_${i + 1}`;
      const client = new QobuzClient({
        appId: config.appId,
        appSecret: config.appSecret,
        secrets: config.secrets || [],
        userAuthToken: config.userAuthToken,
        email: config.email,
        password: config.password
      });

      this.clients.push({
        id,
        userId: config.userId || null,
        client,
        isHealthy: true,
        cooldownUntil: 0,
        totalRequests: 0,
        successfulRequests: 0,
        failedRequests: 0,
        lastError: null,
        lastUsed: 0
      });
    }
  }

  /**
   * Initializes all clients in the pool
   */
  async init() {
    await Promise.all(
      this.clients.map(async (acc) => {
        try {
          await acc.client.init();
          acc.isHealthy = true;
        } catch (err) {
          acc.isHealthy = false;
          acc.lastError = err.message;
          console.warn(`[AccountPool] Failed to initialize account ${acc.id}:`, err.message);
        }
      })
    );
  }

  /**
   * Returns all currently active and healthy accounts (respecting cooldowns)
   */
  getHealthyAccounts() {
    const now = Date.now();
    return this.clients.filter((acc) => {
      if (acc.cooldownUntil > 0 && acc.cooldownUntil > now) {
        return false;
      }
      return acc.isHealthy;
    });
  }

  /**
   * Selects an account with uniform/equal probability across healthy accounts
   */
  selectAccount() {
    const healthy = this.getHealthyAccounts();
    if (healthy.length === 0) {
      // Fallback: If all are in cooldown, pick the one whose cooldown expires soonest
      return this.clients.reduce((soonest, acc) => {
        return acc.cooldownUntil < soonest.cooldownUntil ? acc : soonest;
      }, this.clients[0]);
    }

    // Equal probability round-robin selection with jitter
    const index = this.roundRobinIndex % healthy.length;
    this.roundRobinIndex = (this.roundRobinIndex + 1) % 1000000;
    return healthy[index];
  }

  /**
   * Executes a Qobuz API action with automatic equal-probability load balancing
   * and multi-account failover loop
   */
  async execute(operationName, operationFn) {
    if (this.clients.length === 0) {
      throw new Error("No accounts configured in AccountPool");
    }

    const maxAttempts = this.clients.length;
    let lastError = null;

    // Create a shuffled copy of candidate accounts starting with our evenly chosen account
    const primaryAccount = this.selectAccount();
    const candidates = [
      primaryAccount,
      ...this.clients.filter((c) => c.id !== primaryAccount.id).sort(() => Math.random() - 0.5)
    ];

    for (let attempt = 0; attempt < candidates.length; attempt++) {
      const account = candidates[attempt];
      account.totalRequests++;
      account.lastUsed = Date.now();

      try {
        const result = await operationFn(account.client);
        account.successfulRequests++;
        account.isHealthy = true;
        account.cooldownUntil = 0;
        account.lastError = null;
        return result;
      } catch (err) {
        account.failedRequests++;
        account.lastError = err.message || String(err);
        lastError = err;

        const isAuthError = err.status === 401 || err.message?.includes("token") || err.message?.includes("auth");
        const isRateLimit = err.status === 429 || err.message?.includes("rate limit") || err.message?.includes("too many");

        if (isAuthError) {
          console.error(`[AccountPool] Auth failure for ${account.id}. Disabling account:`, err.message);
          account.isHealthy = false;
        } else if (isRateLimit) {
          // Cooldown for 5 minutes
          account.cooldownUntil = Date.now() + 5 * 60 * 1000;
          console.warn(`[AccountPool] Rate limit hit for ${account.id}. Cooling down for 5 mins.`);
        }

        console.warn(`[AccountPool] Operation '${operationName}' failed on account ${account.id}, retrying with next account... Error:`, err.message);
      }
    }

    throw new Error(`[AccountPool] All ${candidates.length} accounts failed for '${operationName}'. Last error: ${lastError?.message || lastError}`);
  }

  // --- Proxied High-Level API Methods with Load Balancing & Failover ---

  async getTrack(trackId) {
    return this.execute("getTrack", (client) => client.getTrack(trackId));
  }

  async getTrackUrl(trackId, quality = 6, fallback = true) {
    return this.execute("getTrackUrl", (client) => client.getTrackUrl(trackId, quality, fallback));
  }

  async getAlbum(albumId) {
    return this.execute("getAlbum", (client) => client.getAlbum(albumId));
  }

  async getArtist(artistId, extra = "") {
    return this.execute("getArtist", (client) => client.getArtist(artistId, extra));
  }

  async getPlaylist(playlistId) {
    return this.execute("getPlaylist", (client) => client.getPlaylist(playlistId));
  }

  async getLabel(labelId) {
    return this.execute("getLabel", (client) => client.getLabel(labelId));
  }

  async search(query, type = "track", limit = 20, offset = 0) {
    return this.execute("search", (client) => client.search(query, type, limit, offset));
  }

  /**
   * Returns real-time load balancing and account health metrics
   */
  getPoolStatus() {
    const now = Date.now();
    return {
      totalAccounts: this.clients.length,
      healthyAccounts: this.getHealthyAccounts().length,
      accounts: this.clients.map((acc) => ({
        id: acc.id,
        userId: acc.userId,
        appId: acc.client.appId,
        isHealthy: acc.isHealthy,
        inCooldown: acc.cooldownUntil > now,
        cooldownRemainingSec: Math.max(0, Math.round((acc.cooldownUntil - now) / 1000)),
        totalRequests: acc.totalRequests,
        successfulRequests: acc.successfulRequests,
        failedRequests: acc.failedRequests,
        lastError: acc.lastError,
        lastUsed: acc.lastUsed ? new Date(acc.lastUsed).toISOString() : null
      }))
    };
  }
}

/**
 * Helper to construct an AccountPool from Worker environment variables
 */
export function createAccountPoolFromEnv(env) {
  const accounts = [];

  // 1. Try parsing JSON array from QOBUZ_ACCOUNTS_JSON
  if (env.QOBUZ_ACCOUNTS_JSON) {
    try {
      const parsed = JSON.parse(env.QOBUZ_ACCOUNTS_JSON);
      if (Array.isArray(parsed) && parsed.length > 0) {
        return new AccountPool(parsed);
      }
    } catch (e) {
      console.error("[AccountPool] Failed to parse QOBUZ_ACCOUNTS_JSON:", e.message);
    }
  }

  // 2. Scan for indexed accounts: QOBUZ_APP_ID_1, QOBUZ_APP_ID_2...
  let index = 1;
  while (env[`QOBUZ_APP_ID_${index}`] || env[`QOBUZ_USER_AUTH_TOKEN_${index}`]) {
    accounts.push({
      id: `acc_${index}`,
      appId: env[`QOBUZ_APP_ID_${index}`] || env.QOBUZ_APP_ID,
      appSecret: env[`QOBUZ_APP_SECRET_${index}`] || env.QOBUZ_APP_SECRET,
      userAuthToken: env[`QOBUZ_USER_AUTH_TOKEN_${index}`],
      userId: env[`QOBUZ_USER_ID_${index}`]
    });
    index++;
  }

  // 3. Fallback to standard single environment variables
  if (accounts.length === 0) {
    accounts.push({
      id: "primary",
      appId: env.QOBUZ_APP_ID || "798273057",
      appSecret: env.QOBUZ_APP_SECRET || null,
      userAuthToken: env.QOBUZ_USER_AUTH_TOKEN || null,
      userId: env.QOBUZ_USER_ID || null,
      email: env.QOBUZ_EMAIL || null,
      password: env.QOBUZ_PASSWORD || null
    });
  }

  return new AccountPool(accounts);
}

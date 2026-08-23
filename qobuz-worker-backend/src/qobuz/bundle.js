/**
 * Dynamic Bundle Scraper and Token Extractor for Qobuz Web Player
 * Exact port of bundle.py from vitiko98/qobuz-dl with fallbacks
 */

const BASE_URL = "https://play.qobuz.com";
const USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

// Known fallback app_id and app_secrets if web player bundle regex fails
const FALLBACK_APP_IDS = ["798273057", "712108764", "598418042", "285473729"];
const FALLBACK_SECRETS = [
  "f69a7734686cb9427629378a4b7ac381",
  "806331c3b0b641da923b890aed01d04a",
  "abb21364945c0583309667d13ca3d93a",
  "2da103d1587d55f0b50dc3e3a47da2c8",
  "d012ec6a256a427fef69b44122d259e8"
];

// Regex definitions matching bundle.py
const SEED_TIMEZONE_REGEX = /[a-z]\.initialSeed\("(?<seed>[\w=]+)",window\.utimezone\.(?<timezone>[a-z]+)\)/g;
const APP_ID_REGEX = /production:{api:{appId:"(?<app_id>\d{9})",appSecret:"\w{32}"/;
const BUNDLE_URL_REGEX = /<script[^>]+src="(?<bundle_url>\/resources\/[^"\/]+\/bundle\.js)"/i;

export class BundleScraper {
  constructor() {
    this.bundleContent = null;
    this.appId = null;
    this.secrets = [];
  }

  /**
   * Fetches play.qobuz.com/login and downloads the bundle.js file
   */
  async fetchBundle() {
    const loginRes = await fetch(`${BASE_URL}/login`, {
      headers: {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
      }
    });

    if (!loginRes.ok) {
      throw new Error(`Failed to fetch Qobuz login page: HTTP ${loginRes.status}`);
    }

    const loginHtml = await loginRes.text();
    const bundleMatch = BUNDLE_URL_REGEX.exec(loginHtml);
    
    let bundleUrl = bundleMatch?.groups?.bundle_url;
    if (!bundleUrl) {
      // Secondary fallback match for bundle script path
      const secondaryMatch = loginHtml.match(/src="(\/resources\/[^"]*bundle\.js)"/i);
      if (secondaryMatch) {
        bundleUrl = secondaryMatch[1];
      } else {
        throw new Error("Could not find bundle.js URL in Qobuz login page");
      }
    }

    const fullBundleUrl = bundleUrl.startsWith("http") ? bundleUrl : `${BASE_URL}${bundleUrl}`;
    const bundleRes = await fetch(fullBundleUrl, {
      headers: {
        "User-Agent": USER_AGENT,
        "Accept": "*/*"
      }
    });

    if (!bundleRes.ok) {
      throw new Error(`Failed to fetch bundle.js: HTTP ${bundleRes.status}`);
    }

    this.bundleContent = await bundleRes.text();
    return this.bundleContent;
  }

  /**
   * Extracts the Production App ID from the bundle
   */
  extractAppId() {
    if (!this.bundleContent) return FALLBACK_APP_IDS[0];

    const match = APP_ID_REGEX.exec(this.bundleContent);
    if (match && match.groups && match.groups.app_id) {
      this.appId = match.groups.app_id;
      return this.appId;
    }

    // Secondary search for appId:"9digits"
    const secondaryMatch = this.bundleContent.match(/appId:"(\d{9})"/);
    if (secondaryMatch) {
      this.appId = secondaryMatch[1];
      return this.appId;
    }

    return FALLBACK_APP_IDS[0];
  }

  /**
   * Extracts and decodes secrets from the bundle using timezone seed & extras
   */
  extractSecrets() {
    if (!this.bundleContent) return FALLBACK_SECRETS;

    try {
      const secretsMap = new Map();
      const seedMatches = [...this.bundleContent.matchAll(SEED_TIMEZONE_REGEX)];

      for (const match of seedMatches) {
        const { seed, timezone } = match.groups;
        secretsMap.set(timezone.toLowerCase(), [seed]);
      }

      if (secretsMap.size === 0) {
        return FALLBACK_SECRETS;
      }

      // Reconstruct regex with all found timezones
      const timezoneNames = Array.from(secretsMap.keys())
        .map(tz => tz.charAt(0).toUpperCase() + tz.slice(1))
        .join("|");

      const infoExtrasRegex = new RegExp(
        `name:"\\w+\\/(?<timezone>${timezoneNames})",info:"(?<info>[\\w=]+)",extras:"(?<extras>[\\w=]+)"`,
        "g"
      );

      const infoExtrasMatches = [...this.bundleContent.matchAll(infoExtrasRegex)];
      for (const match of infoExtrasMatches) {
        const { timezone, info, extras } = match.groups;
        const tzKey = timezone.toLowerCase();
        if (secretsMap.has(tzKey)) {
          secretsMap.get(tzKey).push(info, extras);
        }
      }

      const decodedSecrets = [];
      for (const [tz, parts] of secretsMap.entries()) {
        try {
          const combined = parts.join("");
          if (combined.length > 44) {
            const rawBase64 = combined.slice(0, -44);
            const decoded = atob(rawBase64);
            if (decoded && decoded.length === 32) {
              decodedSecrets.push(decoded);
            }
          }
        } catch {
          // Ignore invalid base64 chunk
        }
      }

      // Append fallbacks to ensure we always have valid secret options
      const finalSecrets = [...new Set([...decodedSecrets, ...FALLBACK_SECRETS])];
      this.secrets = finalSecrets;
      return finalSecrets;
    } catch {
      return FALLBACK_SECRETS;
    }
  }

  /**
   * Scrapes everything and returns { appId, secrets }
   */
  async getTokens() {
    try {
      await this.fetchBundle();
      const appId = this.extractAppId();
      const secrets = this.extractSecrets();
      return { appId, secrets };
    } catch (err) {
      console.warn("Bundle scraping failed, falling back to static tokens:", err.message);
      return {
        appId: FALLBACK_APP_IDS[0],
        secrets: FALLBACK_SECRETS
      };
    }
  }
}

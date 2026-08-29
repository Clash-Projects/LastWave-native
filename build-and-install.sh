#!/bin/bash
# LastWave Build & Install Script
# This script builds the Android app and installs it on a connected device using ADB.

set -e  # Exit on any error

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored messages
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if ADB is installed
if ! command -v adb &> /dev/null; then
    print_error "ADB is not installed. Please install Android SDK Platform Tools."
    exit 1
fi

# Check if a device is connected
print_info "Checking for connected devices..."
DEVICES=$(adb devices | grep -E "device$" | awk '{print $1}')

if [ -z "$DEVICES" ]; then
    print_error "No devices connected. Please connect an Android device and enable USB debugging."
    exit 1
fi

DEVICE_COUNT=$(echo "$DEVICES" | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" -gt 1 ]; then
    print_warning "Multiple devices connected:"
    echo "$DEVICES"
    echo ""
    read -p "Enter device serial to use (or press Enter to use first device): " SELECTED_DEVICE
    if [ -z "$SELECTED_DEVICE" ]; then
        SELECTED_DEVICE=$(echo "$DEVICES" | head -n 1)
    fi
else
    SELECTED_DEVICE="$DEVICES"
fi

print_success "Using device: $SELECTED_DEVICE"

# Clean the project
print_info "Cleaning project..."
./gradlew clean

# Build the app (debug variant)
print_info "Building app (debug variant)..."
./gradlew assembleDebug

# Check if the APK was built successfully
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    print_error "APK not found at $APK_PATH"
    exit 1
fi

print_success "APK built successfully at $APK_PATH"

# Install the APK on the device
print_info "Installing APK on device $SELECTED_DEVICE..."
adb -s "$SELECTED_DEVICE" install -r "$APK_PATH"

if [ $? -eq 0 ]; then
    print_success "App installed successfully!"
    print_info "You can launch the app from your device's app drawer."
else
    print_error "Failed to install app."
    exit 1
fi

# Optional: Launch the app
read -p "Do you want to launch the app now? (y/n): " LAUNCH_APP
if [ "$LAUNCH_APP" = "y" ] || [ "$LAUNCH_APP" = "Y" ]; then
    print_info "Launching app..."
    # Get the package name from AndroidManifest.xml
    PACKAGE_NAME=$(grep -o 'package="[^"]*"' app/src/main/AndroidManifest.xml | head -n 1 | sed 's/package="//;s/"//')
    adb -s "$SELECTED_DEVICE" shell monkey -p "$PACKAGE_NAME" -c android.intent.category.LAUNCHER 1
    print_success "App launched!"
fi

print_success "Build and install completed!"

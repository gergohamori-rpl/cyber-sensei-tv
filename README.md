# Cyber Sensei TV Player

Native Android TV app for Xiaomi TV 3rd Gen 4K media boxes. Plays media content from the Cyber Sensei server 24/7 with offline support.

## Features

- **ExoPlayer (Media3)** - Native hardware-accelerated video playback
- **Auto-start on boot** - BootReceiver launches the app automatically
- **WakeLock** - Prevents the device from sleeping
- **Offline playback** - Downloads media to local storage
- **OTA updates** - Auto-downloads and installs new APK versions
- **Debug logging** - Sends detailed logs to the server via heartbeat
- **Heartbeat** - Reports device status every 60 seconds

## Architecture

```
SetupActivity → MainActivity → PlaybackService (Foreground)
                    ├── MediaDownloadManager (periodic sync)
                    ├── HeartbeatManager (60s interval)
                    ├── ImageDisplayManager (image slides)
                    └── OtaUpdateManager (version check)
```

## Setup GitHub Actions CI/CD

### 1. Create a GitHub repository

Push this `android-tv-app` directory as the root of a new GitHub repository.

### 2. Add Gradle Wrapper

Generate the Gradle wrapper files locally:

```bash
gradle wrapper --gradle-version 8.5
```

This creates `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`.

### 3. Configure GitHub Secrets

Go to your GitHub repo → Settings → Secrets and variables → Actions → New repository secret:

| Secret Name | Value |
|---|---|
| `CYBER_SENSEI_URL` | Your deployed Cyber Sensei URL (e.g., `https://your-app.replit.app`) |
| `CYBER_SENSEI_API_KEY` | A deploy API key for uploading APKs (set in your server config) |

### 4. Push and Build

```bash
git add .
git commit -m "Initial Android TV app"
git push origin main
```

GitHub Actions will automatically:
1. Build the APK
2. Upload it as a GitHub artifact
3. Deploy it to your Cyber Sensei server

### 5. Install on Xiaomi TV Box

See the Setup Guide tab in the Cyber Sensei TV Manager for detailed installation instructions.

## Manual Build

If you prefer to build locally:

```bash
# Requires Android SDK and JDK 17
./gradlew assembleRelease

# APK will be at:
# app/build/outputs/apk/release/app-release-unsigned.apk
```

## Server API Endpoints

The app communicates with these Cyber Sensei endpoints:

| Endpoint | Method | Description |
|---|---|---|
| `/api/tv/heartbeat` | POST | Send device status + logs |
| `/api/tv/playlist` | GET | Get current playlist |
| `/api/tv/check-update` | GET | Check for app updates |
| `/api/tv/time` | GET | Time synchronization |
| `/api/tv/media/:id/download` | GET | Download media file |
| `/api/tv/download-app` | GET | Download latest APK |

All endpoints require `X-TV-API-Key` header.

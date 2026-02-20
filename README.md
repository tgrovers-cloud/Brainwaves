**🎧 BrainWaves — Spotify DJ Controller**

**Android app that controls Spotify playback using a custom DJ-style interface**

**Why this project matters**

This project demonstrates my ability to build a real-world mobile application that integrates with an external API, handles authentication securely, and implements custom UI interactions.

**What it does**

Lets users link their Spotify account

Play, pause, skip, search, and queue tracks

Browse music using presets (e.g. Chill, House, Focus)

Scrub through a track using a DJ-style jog wheel

Displays live playback position and track info

**Tech Stack**

**Frontend (Android)**

Kotlin

Jetpack Compose

Material 3

Coroutines

**Backend**

Node.js

Express

Spotify Web API

OAuth 2.0

**How it works (simple)**

The Android app handles UI and user interaction.

A Node.js backend handles Spotify authentication and API requests.

Users log in via Spotify; the backend creates a session token.

The app sends this token to the backend for all playback actions.

Spotify secrets stay on the backend — never in the mobile app.

**Key Skills Demonstrated**

Mobile development with Kotlin and Compose

Secure OAuth authentication flows

API integration and backend communication

Custom touch gestures and animations

State management and async programming

**What I learned**

How to structure a mobile + backend project

How OAuth works in a real production-style flow

How to build reusable UI components in Compose

How to debug API integrations end-to-end

**Improvements I’d make next**

Persist sessions instead of in-memory storage

Add better error handling for offline states

Improve test coverage

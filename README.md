🎧 BrainWaves

A Spotify DJ-style controller built with Kotlin, Jetpack Compose, and Node.js

Overview

BrainWaves is an Android app that lets you control Spotify playback using a DJ-inspired interface, similar to a CDJ deck. Users can link their Spotify account, browse presets, search for tracks, queue songs, control playback, and scrub through tracks using a jog wheel.

The project focuses on clean architecture, secure authentication, and custom UI interactions.

Features

🔐 Spotify OAuth login (secure, backend-handled)

▶️ Play, pause, skip tracks

🔍 Search for tracks and queue them

🎛️ DJ-style jog wheel for seeking through a song

🎚️ Preset-based browsing (e.g. Chill, House, Focus)

📊 Real-time playback position and duration

💡 Neon / glass-style UI inspired by CDJ-3000 decks

Tech Stack
Mobile

Kotlin

Jetpack Compose

Material 3

Coroutines for async work

Backend

Node.js

Express

Spotify Web API

OAuth 2.0

In-memory session handling (v1)

Architecture (Simple Explanation)

The Android app handles UI and user interaction.

A Node.js backend handles Spotify authentication and API calls.

When the user links Spotify:

They log in via Spotify in the browser

Spotify redirects back with a session token

The app stores the session token locally

The app sends the session token to the backend for all playback actions.

The backend refreshes Spotify access tokens and talks to Spotify securely.

Why this setup?

No Spotify secrets live in the mobile app

Cleaner separation of concerns

Easier to scale or swap clients later

What I Learned

Implementing OAuth flows securely

Designing custom touch interactions (rotary knobs, jog wheels)

Managing async state between UI and backend

Structuring a real-world mobile + backend project

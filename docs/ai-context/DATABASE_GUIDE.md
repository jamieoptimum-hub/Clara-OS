# Database Guide

## Database Not Currently Implemented

The current Clara Smart Home project does not appear to utilize a standard local relational database (like Room or SQLite) or a structured remote database (like Supabase or Firebase) for storing persistent operational data in the provided context.

State is currently managed in-memory (e.g., `DeviceRepository` caching) or persisted securely via `EncryptedSharedPreferences` (Jetpack Security Crypto).

## Recommendations

1.  **Room Database Integration**: As the app scales (especially for automation rules, historical energy analytics, and caching smart home states), implementing a Room Database is highly recommended.
2.  **CWAS Caching**: The new CWAS widgets (Football, Radio, Commute) currently fetch data directly from Retrofit. Implementing a caching layer (via Room or DataStore) within their Repositories would vastly improve offline performance and API rate-limiting.
3.  **Refactor `DeviceRepository`**: Move the in-memory cache of `SmartTile` objects to a reactive local database (Room) with Flow emissions to properly decouple the network layer from the UI.

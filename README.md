# ChunkCompress 2.0 📦⚡
> **Professional Low-Storage Compression & Recovery Engine for Android**  
> Compress massive folders (e.g. 50 GB) even when your device only has 1 GB, 2 GB, or 5 GB of free disk space, with strict SHA-256 verification, Zip-Slip protection, and zero data loss!

---

## 💡 The Problem & The Solution

### The 50 GB Low-Storage Dilemma
Suppose you downloaded an uncompressed folder of **50 GB** (game files, ROM collection, dataset, or video dump). Your phone's storage is nearly full and only has **2 GB of free space**.
- Standard archiving tools (ZIP, 7z, RAR) **crash or abort immediately** (`ENOSPC: No space left on device`) because they require free space to hold both the original 50 GB files **and** the new archive simultaneously (requiring 50 GB + 25-50 GB free space).

### The ChunkCompress 2.0 Solution
ChunkCompress uses a **transactional, incremental chunk & safe reclaim pipeline**:

```
[ 50 GB Uncompressed Folder ] 
      │
      ▼  (Process Chunk Batch: 500MB, 1GB, 2GB, or 5GB)
┌────────────────────────────────────────────────────────┐
│ 1. Stream Batch into `chunk_001.zip.tmp` (NIO Buffers) │
│    (Calculates real-time streaming SHA-256)            │
├────────────────────────────────────────────────────────┤
│ 2. Atomic Archive Finalization & Package Checksum      │
│    (Flush & sync to disk ➔ rename to `chunk_001.zip`)  │
├────────────────────────────────────────────────────────┤
│ 3. Strict In-Memory Verification Pass                  │
│    (Unzips every entry & verifies SHA-256 checksum)    │
├────────────────────────────────────────────────────────┤
│ 4. Pre-Deletion Source Modification Check              │
│    (Ensures source file attributes have not changed)   │
├────────────────────────────────────────────────────────┤
│ 5. Safe Reclaim Pass (DISABLED by default)             │
│    (ONLY if 100% verified & enabled ➔ Frees 1-2 GB!)   │
├────────────────────────────────────────────────────────┤
│ 6. Durable Atomic Checkpoint in `chunk_manifest.json`  │
└────────────────────────────────────────────────────────┘
      │
      ▼  (Newly freed disk space is reused for next chunk!)
[ Next Batch: `chunk_002.zip` ... until all 50 GB is compressed ]
```

---

## 🛡️ Critical Data Safety Guarantees

1. **Source Deletion Disabled By Default**: Source files are never deleted unless the user explicitly enables source deletion in the settings and confirms via a security prompt.
2. **Strict SHA-256 Verification**: Every archive entry is re-opened, uncompressed in memory, and verified against the original file's SHA-256 hash. If even a 1-byte mismatch or error occurs, deletion is aborted immediately.
3. **Source Modification Detection**: Before deleting any source file, its size and `lastModified` timestamp are checked. If the file was modified on disk while compression was running, deletion is skipped.
4. **Zip-Slip & Path Traversal Protection**: The decompression engine strictly validates canonical paths against the destination directory, rejecting any malicious `../` path escapes.
5. **Crash Recovery & Session Resumability**: If the phone turns off or the process is killed, ChunkCompress detects completed verified chunks and resumes from where it left off without restarting.
6. **Dynamic Storage Emergency Reserve**: Continuously monitors disk free space every ~10 MB written. If free space drops below a **300 MB emergency reserve buffer**, it automatically pauses compression to prevent operating system crashes.
7. **Safe Low-Storage Restoration**: When restoring on low space, archive chunks are deleted ONLY after all files in that chunk have been extracted, verified against the manifest, and committed.

---

## 📱 Navigation & Features

- 📊 **Home Dashboard**: Live storage capacity gauge, active task monitor, quick action tiles, and recent operations list.
- ⚙️ **Compression Setup**: Source/destination folder selectors, automatic chunk-size recommender, compression level picker (Store, Fast, Balanced, Maximum), and pre-flight safety analysis.
- 🔄 **Decompression & Restoration**: File conflict strategies (Auto Rename, Overwrite, Skip), Low-Storage Restore Mode, and Verify-Only Mode (scans archive integrity without writing to disk).
- ⚡ **Live Execution Screen**: Real-time speed (MB/s), elapsed timer, estimated remaining time (ETA), dual progress bars, and functional Pause, Resume, and Cancel buttons.
- 📜 **Session History**: Persistent history database with detailed session breakdown, exportable Markdown reports, and resumption of interrupted sessions.
- 🛠️ **In-App High-Speed Folder Explorer**: Direct filesystem browsing bypassing slow Storage Access Framework (SAF) bottlenecks.
- 🔔 **Android Foreground Service**: CPU WakeLock and persistent notification with Pause/Resume/Cancel actions so long-running 50 GB operations are never terminated by Android.

---

## 🚀 How to Build Using GitHub

Build installable APKs and Google Play App Bundles (AAB) directly on GitHub Actions without needing an Android SDK installed locally:

### Step 1: Push Code to GitHub
```bash
cd /home/deck/Downloads/gg/appand

# Initialize or commit your changes
git add .
git commit -m "feat: ChunkCompress 2.0 Release"

# Add your GitHub repository remote
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPOSITORY.git

# Push to GitHub
git push -u origin main
```

### Step 2: Automated Cloud Compilation
1. Navigate to the **Actions** tab on your GitHub repository.
2. The workflow **Build ChunkCompress 2.0 (APK & AAB)** will automatically trigger.
3. It sets up JDK 17, Android SDK 34, runs the unit test suite, and compiles:
   - `app-debug.apk`
   - `app-release.apk`
   - `app-release.aab` (Android App Bundle)

### Step 3: Download Artifacts
1. Click on the completed workflow run.
2. Under **Artifacts** at the bottom, download **`ChunkCompress-2.0-Artifacts.zip`**.
3. Install `app-debug.apk` or `app-release.apk` on your phone!

> **Automated GitHub Releases:** Pushing a tag (e.g. `git tag v2.0.0 && git push origin v2.0.0`) will automatically build and publish a GitHub Release with the APKs and AAB bundle attached!

---

## 🧪 Automated Test Suite

Run the unit test suite covering zero-byte files, Unicode filenames, multi-chunk verification, Zip-Slip security, source modification detection, and manifest recovery:

```bash
./gradlew testDebugUnitTest
```

---

## 📄 License
MIT License.

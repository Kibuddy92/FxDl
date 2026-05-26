# FxDl
A high-performance desktop download manager wrapping yt-dlp and FFmpeg. Features a secure, context-isolated IPC architecture, multi-threaded concurrent task queue, and real-time stream parsing. Built with fail-safe atomic file writing to guarantee data integrity and prevent config corruption on unexpected app crashes

# FxVideo Download Manager

A high-performance, native desktop GUI wrapper designed for seamless video and playlist downloading. By surfacing the power of `yt-dlp` and `FFmpeg` through an optimized JavaFX user interface, this application provides a robust dashboard for media asset extraction without the complexity of CLI scripting.

The architecture isolates heavy CLI execution on a dedicated background thread pool using Java's `ExecutorService`, ensuring the primary JavaFX Application Thread remains completely unblocked for a fluid, 60 FPS user interface experience.

---

## 📐 Architecture & Core Mechanics

The application utilizes a multi-threaded architecture to decouple visual rendering from process management:

* **JavaFX Application Thread:** Exclusively handles DOM updates, layout rendering, visual progress tracking slider bars, and user interaction events.
* **Background Thread Pool (`ExecutorService`):** Spawns process tasks asynchronously, parses standard I/O command streams in real time, and coordinates task scheduling queues.
* **UI Sync Bridge (`Platform.runLater`):** Safely marshals data parsed by background process threads across the thread boundary back onto the UI thread to update your `TableView` models without race conditions.

### Asynchronous Binary Execution Pipeline

---

## ⚡ Features

- **Concurrent Task Scheduler:** Tracks downloads inside a synchronized queue matrix that caps maximum active operations (managed via your thread pool size). Extraneous links sit in a `QUEUED` state and auto-start sequentially as slots clear.
- **Playlist & Batch Support:** Asynchronously loops through playlist structures, reading target metadata arrays without locking up UI window controls.
- **Fail-Safe Atomic Saves:** Writes tracking states and user properties to absolute configurations (`settings.json`) transactionally via shadow files (`.tmp` swaps) using `VideoPersistence` to shield local history logs from corruption.
- **Dynamic Table Matrix & Context Controls:** Includes right-click handlers for real-time queue priority shifting, selective process halting (`Process.destroy()`), absolute source URL copying, and destructive file-wiping operations from storage disk arrays.
- **Smooth Multiplexing Progress:** Smoothly animates progress bars, transitioning to an indeterminate fluid sliding indicator whenever `FFmpeg` is actively merging distinct high-bitrate visual and audio channels into unified media container formats (e.g., `.mp4` or `.mkv`).

---

## 📦 Prerequisites & System Binaries

Because this application functions as a localized graphical controller utility, **you must supply the core compiled execution engines yourself.** The backend configuration reads binaries explicitly from a native folder structure called `/bin` relative to the application's root execution workspace.

### 1. Structure the Project Directory
Ensure your execution workspace directory is mapped precisely as follows before compiling or launching the Java application:

## Screenshots
<img width="1366" height="768" alt="FxDl Add Video URL dialog" src="https://github.com/user-attachments/assets/223cb46b-0fb0-46e2-92ee-4feb2c21a874" />
<img width="1366" height="768" alt="FxDl Homepage dark mode" src="https://github.com/user-attachments/assets/720ea45a-3f8b-4560-a226-b49cd1d05aa5" />


```text
fxvideo-download-manager/
├── bin/
│   ├── yt-dlp.exe      <-- Place yt-dlp binary here (or "yt-dlp" on Linux/macOS)
│   └── ffmpeg.exe      <-- Place FFmpeg binary here (or "ffmpeg" on Linux/macOS)
├── src/
│   ├── main/
│   │   ├── java/com/videodownloader/
│   │   └── resources/com/videodownloader/
├── pom.xml             <-- (or build.gradle)
└── ...

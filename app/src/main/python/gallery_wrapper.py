"""
gallery_wrapper.py — Simple, sequential gallery-dl wrapper for Chaquopy.

Design:
  - One daemon thread per download, images downloaded one-at-a-time in order.
  - Events pushed to _progress_queue as JSON-serialisable dicts.
  - Kotlin polls poll_event() in a loop on a background coroutine.

Event types:
  log       — {type, download_id, level, message}
  total     — {type, download_id, url, total}
  image     — {type, download_id, url, image_index, filepath}
  complete  — {type, download_id, url, total_images}
  error     — {type, download_id, url, message}
  cancelled — {type, download_id, url}
"""

import os
import threading
import queue as stdlib_queue
import json
import logging
import traceback
import time

from gallery_dl import config, job, exception

# ── Shared progress channel ───────────────────────────────────────────────────
_progress_queue: stdlib_queue.Queue = stdlib_queue.Queue()

# Active download threads: url → Thread  (also keyed by download_id str)
_active_downloads: dict = {}

# Cancelled/paused IDs (download_id str or url str)
_cancelled_ids: set = set()
_cancel_lock = threading.Lock()

# Per-download in-memory log buffer
_logs: dict = {}
_logs_lock = threading.Lock()


# ── Logging helper ────────────────────────────────────────────────────────────

def append_log(download_id: str, message: str, level: str = "INFO") -> None:
    ts = time.strftime("%H:%M:%S")
    line = f"[{ts}][{level}] {message}"
    with _logs_lock:
        buf = _logs.setdefault(str(download_id), [])
        buf.append(line)
        if len(buf) > 1000:
            _logs[str(download_id)] = buf[-1000:]
    _progress_queue.put({
        "type": "log",
        "download_id": str(download_id),
        "level": level,
        "message": message,
    })


def get_logs(download_id: str) -> str:
    with _logs_lock:
        return "\n".join(_logs.get(str(download_id), []))


# ── Public API called from Kotlin ─────────────────────────────────────────────

def poll_event(timeout: float = 0.1):
    """Return next event as a JSON string, or None if queue is empty."""
    try:
        return json.dumps(_progress_queue.get(timeout=timeout))
    except stdlib_queue.Empty:
        return None


def reset_state(download_id: str, url: str) -> None:
    """
    Clear stale state before starting a new download.
    Discards any leftover cancel flags and drains old events for this
    download_id from the shared queue so they don't confuse the new run.
    """
    with _cancel_lock:
        _cancelled_ids.discard(str(download_id))
        _cancelled_ids.discard(str(url))

    # Drain stale events for this download only, keep others
    kept = []
    try:
        while True:
            evt = _progress_queue.get_nowait()
            if evt.get("download_id") != str(download_id):
                kept.append(evt)
    except stdlib_queue.Empty:
        pass
    for evt in kept:
        _progress_queue.put(evt)

    _active_downloads.pop(url, None)
    _active_downloads.pop(str(download_id), None)


def cancel_download(target: str) -> bool:
    """Mark a download (by url or download_id) as cancelled/paused."""
    with _cancel_lock:
        _cancelled_ids.add(str(target))
    return True


def get_active_downloads() -> str:
    return json.dumps(list(_active_downloads.keys()))


def start_download(
    url: str,
    output_dir: str,
    download_id: str,
    cookies_path: str = "",
    extra_config_json: str = "",
    threads: int = 1,      # ignored — always sequential
    sleep_interval: float = 0.5,
) -> None:
    """
    Launch a sequential gallery-dl download in a daemon thread.
    The `threads` parameter is accepted for API compatibility but ignored;
    all downloads run single-threaded for reliability.
    """
    download_id = str(download_id)

    def _run():
        try:
            _run_download(url, output_dir, download_id, cookies_path,
                          extra_config_json, float(sleep_interval))
        except Exception as e:
            tb = traceback.format_exc()
            append_log(download_id, f"Fatal error: {e}\n{tb}", "ERROR")
            _progress_queue.put({
                "type": "error",
                "download_id": download_id,
                "url": url,
                "message": str(e),
            })
        finally:
            _active_downloads.pop(url, None)
            _active_downloads.pop(download_id, None)

    reset_state(download_id, url)
    t = threading.Thread(target=_run, daemon=True, name=f"gdl-{download_id}")
    _active_downloads[url] = t
    _active_downloads[download_id] = t
    t.start()


# ── Download implementation ───────────────────────────────────────────────────

class _QueueLogHandler(logging.Handler):
    """Forwards gallery-dl log records into the progress queue."""
    def __init__(self, download_id: str):
        super().__init__()
        self.download_id = download_id

    def emit(self, record):
        try:
            append_log(self.download_id, self.format(record), record.levelname)
        except Exception:
            pass


def _run_download(
    url: str,
    output_dir: str,
    download_id: str,
    cookies_path: str,
    extra_config_json: str,
    sleep_interval: float,
) -> None:
    """
    Runs inside the daemon thread. Downloads all images sequentially,
    emitting one 'image' event per file as soon as it lands on disk.
    """
    append_log(download_id, f"Starting download: {url}")
    append_log(download_id, f"Output directory: {output_dir}")

    # Attach log handler so gallery-dl messages appear in the log pane
    log_handler = _QueueLogHandler(download_id)
    log_handler.setFormatter(logging.Formatter("[%(name)s] %(message)s"))
    root_logger = logging.getLogger()
    root_logger.setLevel(logging.DEBUG)
    root_logger.addHandler(log_handler)

    try:
        os.makedirs(output_dir, exist_ok=True)

        # ── gallery-dl configuration ──────────────────────────────────────
        config.clear()
        config.set((), "base-directory", output_dir)
        config.set(("extractor",), "base-directory", output_dir)

        if sleep_interval > 0:
            config.set(("extractor",), "sleep", sleep_interval)
            config.set(("extractor",), "sleep-request", sleep_interval)

        if cookies_path and os.path.exists(cookies_path):
            config.set(("extractor",), "cookies", cookies_path)
            append_log(download_id, f"Using cookies: {cookies_path}")

        if extra_config_json:
            try:
                for k, v in json.loads(extra_config_json).items():
                    config.set(("extractor",), k, v)
            except Exception as cfg_err:
                append_log(download_id, f"Config parse warning: {cfg_err}", "WARN")

        # ── State shared between ProgressJob callbacks ────────────────────
        image_count = [0]           # number of images downloaded so far
        total_seen = [0]            # last total we reported to Kotlin

        # ── Custom DownloadJob ────────────────────────────────────────────
        class _ProgressJob(job.DownloadJob):

            def handle_directory(self, kwdict):
                """Called once per album/sub-gallery. Report total count."""
                result = super().handle_directory(kwdict)
                # Try to extract a total image count from the metadata
                tot = (kwdict.get("count") or kwdict.get("total")
                       or kwdict.get("post_count") or kwdict.get("image_count"))
                if tot and isinstance(tot, int) and tot > 0 and tot != total_seen[0]:
                    total_seen[0] = tot
                    _progress_queue.put({
                        "type": "total",
                        "download_id": download_id,
                        "url": url,
                        "total": tot,
                    })
                return result

            def handle_url(self, url_item, kwdict):
                """Called once per image URL. Download, then emit an event."""
                # Check for cancellation before every image
                with _cancel_lock:
                    if download_id in _cancelled_ids or url in _cancelled_ids:
                        raise exception.StopExtraction("Cancelled by user")

                # Let gallery-dl do the actual download (sequential, blocking)
                result = super().handle_url(url_item, kwdict)

                # Resolve the file path from the path-format object
                filepath = ""
                if self.pathfmt:
                    filepath = (str(getattr(self.pathfmt, "realpath", "") or
                                    getattr(self.pathfmt, "path", "") or ""))
                if not filepath:
                    filepath = str(kwdict.get("_filename") or kwdict.get("filename") or "")
                if filepath and not os.path.isabs(filepath):
                    filepath = os.path.join(output_dir, filepath)

                # Verify the file actually exists — log a warning if not
                if filepath and not os.path.isfile(filepath):
                    append_log(download_id,
                               f"Warning: expected file not found: {filepath}", "WARN")
                    filepath = ""

                image_count[0] += 1
                idx = image_count[0]

                if filepath:
                    append_log(download_id,
                               f"Downloaded #{idx}: {os.path.basename(filepath)}")
                else:
                    append_log(download_id,
                               f"Image #{idx} finished but no file path resolved — skipping", "WARN")

                _progress_queue.put({
                    "type": "image",
                    "download_id": download_id,
                    "url": url,
                    "image_index": idx,
                    "filepath": filepath,
                })
                return result

        # ── Run ───────────────────────────────────────────────────────────
        j = _ProgressJob(url)
        status = j.run()

        # ── Determine outcome ─────────────────────────────────────────────
        with _cancel_lock:
            was_cancelled = (download_id in _cancelled_ids or url in _cancelled_ids)

        append_log(download_id,
                   f"gallery-dl finished (status={status}, images={image_count[0]})")

        if was_cancelled:
            append_log(download_id, "Download paused / cancelled.", "INFO")
            _progress_queue.put({
                "type": "cancelled",
                "download_id": download_id,
                "url": url,
            })
        elif status != 0 and image_count[0] == 0:
            msg = f"gallery-dl returned status {status} with 0 images downloaded."
            append_log(download_id, msg, "ERROR")
            _progress_queue.put({
                "type": "error",
                "download_id": download_id,
                "url": url,
                "message": msg,
            })
        else:
            append_log(download_id,
                       f"Complete — {image_count[0]} image(s) downloaded.")
            _progress_queue.put({
                "type": "complete",
                "download_id": download_id,
                "url": url,
                "total_images": image_count[0],
            })

    except exception.StopExtraction:
        append_log(download_id, "Download stopped (paused or cancelled).", "INFO")
        _progress_queue.put({
            "type": "cancelled",
            "download_id": download_id,
            "url": url,
        })

    except Exception as e:
        tb = traceback.format_exc()
        append_log(download_id, f"Unexpected error: {e}\n{tb}", "ERROR")
        _progress_queue.put({
            "type": "error",
            "download_id": download_id,
            "url": url,
            "message": str(e),
        })

    finally:
        root_logger.removeHandler(log_handler)
        # Clean up cancel flags so they don't affect the next download
        with _cancel_lock:
            _cancelled_ids.discard(download_id)
            _cancelled_ids.discard(url)

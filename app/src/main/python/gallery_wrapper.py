"""
gallery_wrapper.py — Wraps gallery-dl for use from Kotlin via Chaquopy.
Provides download execution with progress reporting, priority support, and live logging.
"""
import os
import sys
import threading
import queue as stdlib_queue
import json
import logging
import traceback
import time
from gallery_dl import config, job, output, exception, path, downloader

# ── Shared progress channel (Python writes, Kotlin reads) ──────────────────
_progress_queue: stdlib_queue.Queue = stdlib_queue.Queue()
# Active downloads: url → thread
_active_downloads: dict = {}
# Active download_id → thread mapping (parallel to _active_downloads)
_active_download_ids: dict = {}
# Priority indices: url/download_id → set/list of int indices
_priority_indices: dict = {}
_priority_order: dict = {}
_priority_lock = threading.Lock()
# Live logs by download_id: download_id → list of str
_logs_by_download: dict = {}
_logs_lock = threading.Lock()
# Cancelled / paused download ids or urls
_cancelled_ids: set = set()


def reset_state(download_id: str, url: str) -> None:
    """
    Clear all stale state for a download before starting it.
    Drains any leftover events from the queue that belong to this download_id
    so a previous run's cancelled/error events don't confuse the new download.
    """
    global _progress_queue
    with _logs_lock:
        _cancelled_ids.discard(str(download_id))
        _cancelled_ids.discard(str(url))

    # Drain stale events for this download_id from the queue
    drained = []
    try:
        while True:
            event = _progress_queue.get_nowait()
            if event.get("download_id") != str(download_id):
                drained.append(event)  # keep events for other downloads
    except stdlib_queue.Empty:
        pass
    for evt in drained:
        _progress_queue.put(evt)

    # Clean up any dead threads
    _active_downloads.pop(url, None)
    _active_download_ids.pop(str(download_id), None)



def get_progress_queue() -> stdlib_queue.Queue:
    """Return the shared progress queue. Kotlin calls this to get events."""
    return _progress_queue


def poll_event(timeout: float = 0.1):
    """
    Poll one event from the queue. Returns a JSON string or None.
    Kotlin can call this in a polling loop on a background thread.
    """
    try:
        event = _progress_queue.get(timeout=timeout)
        return json.dumps(event)
    except stdlib_queue.Empty:
        return None


def append_log(download_id: str, message: str, level: str = "INFO") -> None:
    """Append a log line to both in-memory store and progress queue."""
    timestamp = time.strftime("%H:%M:%S")
    formatted = f"[{timestamp}][{level}] {message}"
    with _logs_lock:
        if download_id not in _logs_by_download:
            _logs_by_download[download_id] = []
        _logs_by_download[download_id].append(formatted)
        if len(_logs_by_download[download_id]) > 1000:
            _logs_by_download[download_id] = _logs_by_download[download_id][-1000:]

    _progress_queue.put({
        "type": "log",
        "download_id": download_id,
        "level": level,
        "message": message,
    })


def get_logs(download_id: str) -> str:
    """Return all captured logs for a download as newline-separated text."""
    with _logs_lock:
        lines = _logs_by_download.get(str(download_id), [])
        return "\n".join(lines)


def set_priority_indices(target: str, indices_csv: str) -> None:
    """
    Update the high-priority indices for a download (by url or download_id).
    indices_csv: comma-separated ordered integer indices.
    """
    try:
        indices = [int(i) for i in str(indices_csv).split(",") if i.strip()]
        with _priority_lock:
            _priority_indices[str(target)] = set(indices)
            _priority_order[str(target)] = indices
    except Exception:
        pass


class _QueueLogHandler(logging.Handler):
    """Intercepts gallery-dl logging and sends to progress queue & log buffer."""
    def __init__(self, download_id: str, url: str):
        super().__init__()
        self.download_id = download_id
        self.url = url

    def emit(self, record):
        try:
            msg = self.format(record)
            append_log(self.download_id, msg, record.levelname.upper())
        except Exception:
            pass


def start_download(
    url: str,
    output_dir: str,
    download_id: str,
    cookies_path: str = "",
    extra_config_json: str = "",
    threads: int = 3,
    sleep_interval: float = 0.5,
) -> None:
    """
    Start a gallery-dl download in a daemon thread.
    Events are pushed to _progress_queue as dicts:
      {type: 'start' | 'image' | 'complete' | 'error' | 'log',
       download_id: str, url: str, ...}
    """
    def _run():
        try:
            _run_download(
                url,
                output_dir,
                download_id,
                cookies_path,
                extra_config_json,
                threads=int(threads),
                sleep_interval=float(sleep_interval),
            )
        except Exception as e:
            tb = traceback.format_exc()
            append_log(download_id, f"Fatal error: {e}\n{tb}", "ERROR")
            _progress_queue.put({
                "type": "error",
                "download_id": download_id,
                "url": url,
                "message": f"{str(e)}: {tb}",
            })
        finally:
            _active_downloads.pop(url, None)
            _active_download_ids.pop(str(download_id), None)

    # Clear stale state from any previous run with this id/url
    reset_state(str(download_id), url)

    t = threading.Thread(target=_run, daemon=True, name=f"gdl-{download_id}")
    _active_downloads[url] = t
    _active_download_ids[str(download_id)] = t
    t.start()



def _run_download(
    url: str,
    output_dir: str,
    download_id: str,
    cookies_path: str,
    extra_config_json: str,
    threads: int = 3,
    sleep_interval: float = 0.5,
) -> None:
    """Internal: configure gallery-dl, setup logging, and run the download job."""
    num_threads = max(1, min(int(threads), 8))
    sleep_val = max(0.0, float(sleep_interval))

    append_log(download_id, f"Initializing gallery-dl engine for {url}")
    append_log(download_id, f"Target directory: {output_dir}")
    append_log(download_id, f"Download configuration: threads={num_threads}, sleep={sleep_val}s")

    # Set up logging interceptor
    handler = _QueueLogHandler(download_id, url)
    handler.setFormatter(logging.Formatter("[%(name)s] %(message)s"))
    root_logger = logging.getLogger()
    gdl_logger = logging.getLogger("gallery-dl")
    root_logger.setLevel(logging.DEBUG)
    root_logger.addHandler(handler)
    gdl_logger.addHandler(handler)

    # Ensure working directory is the writable output directory so relative opens don't hit /
    try:
        os.makedirs(output_dir, exist_ok=True)
        os.chdir(output_dir)
        append_log(download_id, f"Working directory set to: {os.getcwd()}")
    except Exception as e:
        append_log(download_id, f"Warning: could not set working directory to {output_dir}: {e}", "WARN")

    pending_tasks = []
    task_cv = threading.Condition()
    worker_threads = []
    stop_workers = threading.Event()
    image_count = [0]
    seq_counter = [0]
    count_lock = threading.Lock()
    seen_subgalleries = set()
    total_accumulated = [0]
    # Tracks how many workers are actively executing a download (not idle/waiting)
    inflight_count = [0]
    inflight_lock = threading.Lock()


    def _report_subgallery_or_total(job_instance, kwdict):
        tot = kwdict.get("count") or kwdict.get("total") or kwdict.get("post_count")
        if not (tot and isinstance(tot, int) and tot > 0):
            return

        title = str(kwdict.get("title") or kwdict.get("album") or kwdict.get("post_id") or kwdict.get("id") or "")
        sub_key = f"{getattr(job_instance.extractor, 'subcategory', '')}_{title or id(job_instance)}"

        if getattr(job_instance, "is_child", False):
            # Sub-gallery in a collection / multi-gallery
            if sub_key not in seen_subgalleries:
                seen_subgalleries.add(sub_key)
                total_accumulated[0] += tot
                sub_label = f"'{title}' " if title else ""
                append_log(download_id, f"Sub-gallery {sub_label}({tot} images) — total: {total_accumulated[0]}")
                _progress_queue.put({
                    "type": "total",
                    "download_id": download_id,
                    "url": url,
                    "total": total_accumulated[0],
                })
        else:
            # Top-level single gallery
            if tot != total_accumulated[0]:
                total_accumulated[0] = tot
                append_log(download_id, f"Gallery reports total of {tot} images")
                _progress_queue.put({
                    "type": "total",
                    "download_id": download_id,
                    "url": url,
                    "total": tot,
                })

    try:
        config.clear()
        config.set((), "base-directory", output_dir)
        config.set(("extractor",), "base-directory", output_dir)
        if sleep_val > 0:
            config.set(("extractor",), "sleep", sleep_val)
            config.set(("extractor",), "sleep-request", sleep_val)

        if cookies_path and os.path.exists(cookies_path):
            append_log(download_id, f"Using cookies from: {cookies_path}")
            config.set(("extractor",), "cookies", cookies_path)
        else:
            append_log(download_id, "No cookies provided (session may be anonymous)")

        # Apply user-supplied extra config (Settings screen JSON)
        if extra_config_json:
            try:
                extra = json.loads(extra_config_json)
                for key, value in extra.items():
                    config.set(("extractor",), key, value)
                append_log(download_id, f"Applied custom config: {list(extra.keys())}")
            except Exception as e:
                append_log(download_id, f"Failed to parse extra config JSON: {e}", "WARNING")

        _progress_queue.put({
            "type": "start",
            "download_id": download_id,
            "url": url,
        })
        append_log(download_id, f"Scanning URL: {url}...")

        class _ProgressJob(job.DownloadJob):
            def __init__(self, extr, parent=None):
                super().__init__(extr, parent)
                self.is_child = parent is not None

            def handle_directory(self, kwdict):
                result = super().handle_directory(kwdict)
                if self.pathfmt:
                    if not self.pathfmt.realdirectory or not os.path.isabs(self.pathfmt.realdirectory):
                        sub = self.pathfmt.realdirectory.lstrip("/\\") if self.pathfmt.realdirectory else ""
                        self.pathfmt.realdirectory = os.path.join(output_dir, sub)
                        if not self.pathfmt.realdirectory.endswith(os.sep):
                            self.pathfmt.realdirectory += os.sep
                    if not self.pathfmt.directory or not os.path.isabs(self.pathfmt.directory):
                        sub = self.pathfmt.directory.lstrip("/\\") if self.pathfmt.directory else ""
                        self.pathfmt.directory = os.path.join(output_dir, sub)
                        if not self.pathfmt.directory.endswith(os.sep):
                            self.pathfmt.directory += os.sep
                    try:
                        os.makedirs(self.pathfmt.realdirectory, exist_ok=True)
                    except Exception:
                        pass

                _report_subgallery_or_total(self, kwdict)
                return result

            def handle_url(self, url_item, kwdict):
                """Called per image — download directly or delegate to worker pool."""
                with _logs_lock:
                    if str(download_id) in _cancelled_ids or str(url) in _cancelled_ids:
                        append_log(download_id, "Download paused/cancelled by user.", "WARN")
                        raise exception.StopExtraction("User paused or cancelled download")

                # Track sub-gallery or overall total count
                _report_subgallery_or_total(self, kwdict)

                if num_threads <= 1:
                    # Sequential path
                    if sleep_val > 0:
                        time.sleep(sleep_val)

                    append_log(download_id, f"Downloading image: {url_item}")
                    result = super().handle_url(url_item, kwdict)
                    image_count[0] += 1

                    filepath = ""
                    if hasattr(self, "pathfmt") and self.pathfmt:
                        filepath = str(getattr(self.pathfmt, "realpath", "") or getattr(self.pathfmt, "path", "") or "")
                    if not filepath and "_filename" in kwdict:
                        filepath = str(kwdict["_filename"])
                    if not filepath and "filename" in kwdict:
                        filepath = str(kwdict["filename"])
                    if filepath and not os.path.isabs(filepath):
                        filepath = os.path.join(output_dir, filepath)

                    append_log(download_id, f"Saved image #{image_count[0]}: {os.path.basename(filepath) if filepath else url_item}")
                    _progress_queue.put({
                        "type": "image",
                        "download_id": download_id,
                        "url": url,
                        "image_index": image_count[0],
                        "filepath": filepath,
                    })
                    return result
                else:
                    # Parallel worker path: assign index and enqueue
                    with count_lock:
                        seq_counter[0] += 1
                        idx = seq_counter[0]

                    with task_cv:
                        while not stop_workers.is_set() and len(pending_tasks) >= 200:
                            with _logs_lock:
                                if str(download_id) in _cancelled_ids or str(url) in _cancelled_ids:
                                    raise exception.StopExtraction("User paused or cancelled download")
                            task_cv.wait(timeout=0.2)

                        if stop_workers.is_set():
                            raise exception.StopExtraction("Workers stopped")

                        pending_tasks.append((idx, url_item, kwdict.copy()))
                        task_cv.notify()

                    return True

        j = _ProgressJob(url)

        if num_threads > 1:
            def _worker_loop(w_id):
                while not stop_workers.is_set():
                    task = None
                    with task_cv:
                        while not stop_workers.is_set() and not pending_tasks:
                            task_cv.wait(timeout=0.2)

                        if stop_workers.is_set() and not pending_tasks:
                            break

                        if not pending_tasks:
                            continue

                        # Check priority indices (viewport visible images, ordered window)
                        with _priority_lock:
                            prios = _priority_indices.get(str(url), set()) | _priority_indices.get(str(download_id), set())
                            prio_order = _priority_order.get(str(download_id), []) or _priority_order.get(str(url), [])

                        best_i = 0
                        if prio_order:
                            found = False
                            for target_prio in prio_order:
                                for i, t in enumerate(pending_tasks):
                                    t_idx = t[0]
                                    # Match exact index (both 1-based and 0-based conversions)
                                    if t_idx == target_prio or (t_idx - 1) == target_prio or (t_idx + 1) == target_prio:
                                        best_i = i
                                        found = True
                                        break
                                if found:
                                    break
                        elif prios:
                            for i, t in enumerate(pending_tasks):
                                t_idx = t[0]
                                if t_idx in prios or (t_idx - 1) in prios:
                                    best_i = i
                                    break

                        task = pending_tasks.pop(best_i)
                        task_cv.notify_all()

                    if task is None:
                        continue

                    # Mark as in-flight BEFORE dequeuing so drain loop sees it
                    with inflight_lock:
                        inflight_count[0] += 1

                    idx, u_item, kw = task
                    try:
                        with _logs_lock:
                            if str(download_id) in _cancelled_ids or str(url) in _cancelled_ids:
                                # Still emit so the slot is not silently stuck
                                _progress_queue.put({
                                    "type": "image",
                                    "download_id": download_id,
                                    "url": url,
                                    "image_index": idx,
                                    "filepath": "",
                                })
                                with count_lock:
                                    image_count[0] += 1
                                continue

                        if sleep_val > 0:
                            time.sleep(sleep_val)

                        # Build thread-isolated PathFormat
                        tp = path.PathFormat(j.extractor)
                        tp.basedirectory = output_dir if output_dir.endswith(os.sep) else (output_dir + os.sep)

                        # Set directory from job's directory kwdict or item kwdict
                        dir_kw = (getattr(j.pathfmt, "kwdict", None) if hasattr(j, "pathfmt") and j.pathfmt else None) or kw
                        tp.set_directory(dir_kw)
                        tp.set_filename(kw)
                        tp.build_path()

                        # Ensure directories and paths are strictly absolute under output_dir
                        if not tp.realdirectory or not os.path.isabs(tp.realdirectory):
                            sub = tp.realdirectory.lstrip("/\\") if tp.realdirectory else ""
                            tp.realdirectory = os.path.join(output_dir, sub)
                        if not tp.realdirectory.endswith(os.sep):
                            tp.realdirectory += os.sep

                        if not tp.directory or not os.path.isabs(tp.directory):
                            sub = tp.directory.lstrip("/\\") if tp.directory else ""
                            tp.directory = os.path.join(output_dir, sub)
                        if not tp.directory.endswith(os.sep):
                            tp.directory += os.sep

                        os.makedirs(tp.realdirectory, exist_ok=True)

                        if not tp.realpath or not os.path.isabs(tp.realpath):
                            fname = os.path.basename(tp.realpath) if tp.realpath else (tp.filename or os.path.basename(u_item.split("?")[0]))
                            tp.realpath = os.path.join(tp.realdirectory, fname)

                        if not tp.path or not os.path.isabs(tp.path):
                            tp.path = tp.realpath

                        if not tp.temppath or not os.path.isabs(tp.temppath):
                            fname = os.path.basename(tp.temppath) if tp.temppath else os.path.basename(tp.realpath)
                            tp.temppath = os.path.join(tp.realdirectory, fname)

                        # Use factory functions to avoid closure capture bugs across loop iterations
                        def _make_safe_open(bound_tp):
                            orig = bound_tp.open
                            def _safe_open(mode="wb"):
                                if not os.path.isabs(bound_tp.temppath):
                                    bound_tp.temppath = os.path.join(bound_tp.realdirectory, os.path.basename(bound_tp.temppath))
                                os.makedirs(os.path.dirname(bound_tp.temppath), exist_ok=True)
                                return orig(mode)
                            return _safe_open

                        def _make_safe_finalize(bound_tp):
                            orig = bound_tp.finalize
                            def _safe_finalize():
                                if not os.path.isabs(bound_tp.temppath):
                                    bound_tp.temppath = os.path.join(bound_tp.realdirectory, os.path.basename(bound_tp.temppath))
                                if not os.path.isabs(bound_tp.realpath):
                                    bound_tp.realpath = os.path.join(bound_tp.realdirectory, os.path.basename(bound_tp.realpath))
                                os.makedirs(os.path.dirname(bound_tp.realpath), exist_ok=True)
                                return orig()
                            return _safe_finalize

                        tp.open = _make_safe_open(tp)
                        tp.finalize = _make_safe_finalize(tp)

                        filepath = ""

                        # tp.exists() check — only trust it if the resolved path is a real file.
                        # tp.exists() can return True with an empty/relative realpath (false-positive),
                        # which would leave filepath="" and the image stuck as "downloading".
                        if tp.exists():
                            candidate = str(tp.realpath or tp.path or "")
                            if candidate and os.path.isabs(candidate) and os.path.isfile(candidate):
                                filepath = candidate
                                append_log(download_id, f"[Worker-{w_id}] Image #{idx} already on disk, skipping download")

                        if not filepath:
                            scheme = u_item[:u_item.find(":")] if ":" in u_item else "https"
                            dl_cls = downloader.find(scheme)
                            if dl_cls is None:
                                append_log(download_id,
                                    f"[Worker-{w_id}] No downloader found for scheme '{scheme}' on image #{idx} — skipping",
                                    "WARN")
                            else:
                                # Wrap in a thread with a 2-minute per-image timeout.
                                # A hung HTTP connection would otherwise block this worker
                                # forever, preventing all subsequent queued images from running.
                                import concurrent.futures as _cf
                                thread_dl = dl_cls(j)
                                with _cf.ThreadPoolExecutor(max_workers=1, thread_name_prefix=f"gdl-dl-{w_id}") as _ex:
                                    _fut = _ex.submit(thread_dl.download, u_item, tp)
                                    try:
                                        success = _fut.result(timeout=120)
                                    except _cf.TimeoutError:
                                        append_log(download_id,
                                            f"[Worker-{w_id}] Timeout (120s) downloading image #{idx}: {u_item}",
                                            "WARN")
                                        success = False
                                    except Exception as _dl_err:
                                        append_log(download_id,
                                            f"[Worker-{w_id}] Downloader error on image #{idx}: {_dl_err}",
                                            "WARN")
                                        success = False
                                if success:
                                    tp.finalize()
                                    candidate = str(tp.realpath or tp.path or "")
                                    if candidate and os.path.isabs(candidate):
                                        filepath = candidate

                        if not filepath and hasattr(j, "pathfmt") and j.pathfmt:
                            filepath = str(getattr(j.pathfmt, "realpath", "") or getattr(j.pathfmt, "path", "") or "")
                        if not filepath and "_filename" in kw:
                            filepath = str(kw["_filename"])
                        if filepath and not os.path.isabs(filepath):
                            filepath = os.path.join(output_dir, filepath)

                        with count_lock:
                            image_count[0] += 1

                        append_log(download_id, f"[Worker-{w_id}] Saved image #{idx}: {os.path.basename(filepath) if filepath else u_item}")
                        _progress_queue.put({
                            "type": "image",
                            "download_id": download_id,
                            "url": url,
                            "image_index": idx,
                            "filepath": filepath,
                        })
                    except Exception as ex:
                        tb = traceback.format_exc()
                        append_log(download_id, f"Worker-{w_id} error downloading #{idx}: {ex}\n{tb}", "WARN")
                        # Always emit even on exception — a missing event would leave the
                        # slot stuck as "downloading" and the final count would be wrong.
                        with count_lock:
                            image_count[0] += 1
                        _progress_queue.put({
                            "type": "image",
                            "download_id": download_id,
                            "url": url,
                            "image_index": idx,
                            "filepath": "",
                        })
                    finally:
                        # Always decrement inflight and wake the drain loop
                        with inflight_lock:
                            inflight_count[0] -= 1
                        with task_cv:
                            task_cv.notify_all()

            for i in range(num_threads):
                t = threading.Thread(target=_worker_loop, args=(i + 1,), daemon=True, name=f"gdl-w-{download_id}-{i}")
                worker_threads.append(t)
                t.start()

        status = j.run()

        if num_threads > 1:
            # Drain: wait until ALL tasks are dequeued AND all in-flight workers finish.
            # Checking only pending_tasks is wrong — workers dequeue before they download,
            # so pending_tasks can be empty while 20+ downloads are still in progress.
            while True:
                with inflight_lock:
                    current_inflight = inflight_count[0]
                with task_cv:
                    current_pending = len(pending_tasks)
                if (current_pending == 0 and current_inflight == 0) or stop_workers.is_set():
                    break
                with task_cv:
                    task_cv.wait(timeout=0.3)

            stop_workers.set()
            with task_cv:
                task_cv.notify_all()
            for t in worker_threads:
                # Allow generous time for any last in-flight HTTP download to complete
                t.join(timeout=120.0)

        append_log(download_id, f"Execution completed with exit code: {status}, images downloaded: {image_count[0]}")

        with _logs_lock:
            was_cancelled = str(download_id) in _cancelled_ids or str(url) in _cancelled_ids

        if was_cancelled:
            append_log(download_id, "Download paused/cancelled.", "INFO")
            _progress_queue.put({
                "type": "cancelled",
                "download_id": download_id,
                "url": url,
            })
        elif status != 0 and image_count[0] == 0:
            msg = f"gallery-dl returned status code {status}. No images downloaded."
            append_log(download_id, msg, "ERROR")
            _progress_queue.put({
                "type": "error",
                "download_id": download_id,
                "url": url,
                "message": msg,
            })
        else:
            _progress_queue.put({
                "type": "complete",
                "download_id": download_id,
                "url": url,
                "total_images": image_count[0],
            })
    except exception.StopExtraction:
        if num_threads > 1:
            stop_workers.set()
            with task_cv:
                pending_tasks.clear()
                task_cv.notify_all()
        append_log(download_id, "Download stopped (paused or cancelled).", "INFO")
        _progress_queue.put({
            "type": "cancelled",
            "download_id": download_id,
            "url": url,
        })
    except Exception as e:
        if num_threads > 1:
            stop_workers.set()
            with task_cv:
                pending_tasks.clear()
                task_cv.notify_all()
        tb = traceback.format_exc()
        append_log(download_id, f"Download failed: {e}\n{tb}", "ERROR")
        _progress_queue.put({
            "type": "error",
            "download_id": download_id,
            "url": url,
            "message": f"{str(e)}: {tb}",
        })
    finally:
        if num_threads > 1:
            stop_workers.set()
            with task_cv:
                task_cv.notify_all()
        root_logger.removeHandler(handler)
        gdl_logger.removeHandler(handler)
        _active_downloads.pop(url, None)
        with _logs_lock:
            _cancelled_ids.discard(str(download_id))
            _cancelled_ids.discard(str(url))


def cancel_download(target: str) -> bool:
    """
    Request cancellation or pause by download_id or url.
    Only sets the cancellation flag — the running download thread will detect
    it and emit the 'cancelled' event itself. We must NOT push a 'cancelled'
    event here because it would be consumed by the next download's polling loop.
    """
    target_str = str(target)
    with _logs_lock:
        _cancelled_ids.add(target_str)
    append_log(target_str, "Pause/Cancel signal received", "WARN")
    return True


def get_active_downloads() -> str:
    """Return JSON list of active download URLs."""
    return json.dumps(list(_active_downloads.keys()))

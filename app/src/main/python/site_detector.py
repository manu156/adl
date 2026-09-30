"""
site_detector.py — Checks whether a URL is supported by gallery-dl.
Called from Kotlin via Chaquopy.
"""
from gallery_dl import extractor


def is_supported(url: str) -> bool:
    """Return True if gallery-dl has an extractor for this URL."""
    try:
        if not url or not url.startswith(("http://", "https://")):
            return False
        ex = extractor.find(url)
        return ex is not None
    except Exception:
        return False


def get_gallery_name(url: str) -> str:
    """Return a human-readable site/category name for display on the FAB."""
    try:
        ex = extractor.find(url)
        if ex:
            return ex.category.replace("-", " ").title()
    except Exception:
        pass
    return "Gallery"


def get_extractor_info(url: str) -> dict:
    """Return full extractor info dict for diagnostics."""
    try:
        ex = extractor.find(url)
        if ex:
            return {
                "category": ex.category,
                "subcategory": ex.subcategory,
                "scheme": ex.scheme,
            }
    except Exception:
        pass
    return {}

"""
config_helper.py — Reads and validates gallery-dl config JSON for the Settings screen.
"""
import json
from gallery_dl import config

DEFAULT_CONFIG = {
    "filename-template": "{filename}.{extension}",
    "sleep": 0.5,
    "retries": 4,
    "timeout": 30,
    "verify": True,
    "part-directory": None,
    "user-agent": "gallery-dl/1.0",
}


def get_default_config_json() -> str:
    """Return the default config as a pretty JSON string."""
    return json.dumps(DEFAULT_CONFIG, indent=2)


def validate_config_json(json_str: str) -> str:
    """
    Validate a user-supplied config JSON string.
    Returns '' if valid, or an error message string.
    """
    try:
        parsed = json.loads(json_str)
        if not isinstance(parsed, dict):
            return "Config must be a JSON object"
        return ""
    except json.JSONDecodeError as e:
        return f"JSON parse error: {e}"


def apply_config_json(json_str: str) -> str:
    """Apply config JSON to gallery-dl runtime config. Returns '' or error."""
    error = validate_config_json(json_str)
    if error:
        return error
    try:
        cfg = json.loads(json_str)
        for key, value in cfg.items():
            config.set(("extractor",), key, value)
        return ""
    except Exception as e:
        return str(e)

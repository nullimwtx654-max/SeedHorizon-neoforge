#!/usr/bin/env python3
"""
Upload extracted zip contents to GitHub repo using GitHub API.
Handles large number of files by batching tree creation.
"""

import os
import sys
import base64
import json
import hashlib
import mimetypes
import subprocess
from pathlib import Path
from typing import Dict, List, Optional, Tuple
import requests

# Configuration
GITHUB_TOKEN = os.environ.get('GITHUB_TOKEN')
if not GITHUB_TOKEN:
    # Try to read from the harness token file
    token_file = Path("/data/user/0/com.androidharness.app/files/linux/home/.gh-token")
    if token_file.exists():
        GITHUB_TOKEN = token_file.read_text().strip()

OWNER = "nullimwtx654-max"
REPO = "githls"
BRANCH = "main"
BASE_URL = f"https://api.github.com/repos/{OWNER}/{REPO}"
HEADERS = {
    "Authorization": f"Bearer {GITHUB_TOKEN}",
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
}

EXTRACT_DIR = Path("/storage/emulated/0/Android/data/com.androidharness.app/files/workspace/githls-upload")
COMMIT_MESSAGE = "Replace repository contents with pocketpal-ai-enhanced (17)"


def api_request(method: str, url: str, **kwargs) -> requests.Response:
    """Make an API request with error handling."""
    response = requests.request(method, url, headers=HEADERS, timeout=60, **kwargs)
    if response.status_code >= 400:
        print(f"API Error {response.status_code}: {response.text}")
        response.raise_for_status()
    return response


def get_current_head_sha() -> str:
    """Get the current HEAD commit SHA."""
    resp = api_request("GET", f"{BASE_URL}/git/refs/heads/{BRANCH}")
    return resp.json()["object"]["sha"]


def get_commit_tree_sha(commit_sha: str) -> str:
    """Get the tree SHA from a commit."""
    resp = api_request("GET", f"{BASE_URL}/git/commits/{commit_sha}")
    return resp.json()["tree"]["sha"]


def create_blob(content: bytes, encoding: str = "utf-8") -> str:
    """Create a blob and return its SHA."""
    if encoding == "base64":
        content_b64 = base64.b64encode(content).decode("ascii")
        data = {"content": content_b64, "encoding": "base64"}
    else:
        data = {"content": content.decode(encoding), "encoding": "utf-8"}
    
    resp = api_request("POST", f"{BASE_URL}/git/blobs", json=data)
    return resp.json()["sha"]


def create_tree(base_tree_sha: str, tree_items: List[Dict]) -> str:
    """Create a tree and return its SHA."""
    data = {"base_tree": base_tree_sha, "tree": tree_items}
    resp = api_request("POST", f"{BASE_URL}/git/trees", json=data)
    return resp.json()["sha"]


def create_commit(message: str, tree_sha: str, parent_sha: str) -> str:
    """Create a commit and return its SHA."""
    data = {
        "message": message,
        "tree": tree_sha,
        "parents": [parent_sha],
    }
    resp = api_request("POST", f"{BASE_URL}/git/commits", json=data)
    return resp.json()["sha"]


def update_ref(commit_sha: str) -> None:
    """Update the branch ref to point to the new commit."""
    data = {"sha": commit_sha, "force": True}
    api_request("PATCH", f"{BASE_URL}/git/refs/heads/{BRANCH}", json=data)


def is_binary_file(filepath: Path) -> bool:
    """Check if a file is binary."""
    mime_type, _ = mimetypes.guess_type(str(filepath))
    if mime_type and mime_type.startswith(("image/", "font/", "video/", "audio/", "application/")):
        return True
    # Check for common binary extensions
    binary_exts = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".svg", 
                   ".woff", ".woff2", ".ttf", ".eot", ".otf",
                   ".zip", ".gz", ".tar", ".rar", ".7z",
                   ".pdf", ".doc", ".docx", ".xls", ".xlsx",
                   ".pyc", ".so", ".dll", ".exe", ".bin", ".dat"}
    return filepath.suffix.lower() in binary_exts


def read_file_content(filepath: Path) -> Tuple[bytes, str]:
    """Read file content and determine encoding."""
    if is_binary_file(filepath):
        content = filepath.read_bytes()
        return content, "base64"
    else:
        content = filepath.read_bytes()
        # Validate UTF-8
        try:
            content.decode("utf-8")
            return content, "utf-8"
        except UnicodeDecodeError:
            return content, "base64"


def main():
    print("Starting upload to GitHub...")
    print(f"Extract directory: {EXTRACT_DIR}")
    print(f"Repo: {OWNER}/{REPO}, Branch: {BRANCH}")
    
    # Get current HEAD
    head_sha = get_current_head_sha()
    print(f"Current HEAD: {head_sha}")
    
    base_tree_sha = get_commit_tree_sha(head_sha)
    print(f"Base tree: {base_tree_sha}")
    
    # Collect all files
    files = []
    for filepath in EXTRACT_DIR.rglob("*"):
        if filepath.is_file():
            rel_path = filepath.relative_to(EXTRACT_DIR)
            files.append((str(rel_path).replace("\\", "/"), filepath))
    
    print(f"Found {len(files)} files to upload")
    
    # Process files in batches to create blobs
    tree_items = []
    batch_size = 100  # GitHub recommends keeping tree size reasonable
    
    for i, (rel_path, filepath) in enumerate(files):
        content, encoding = read_file_content(filepath)
        blob_sha = create_blob(content, encoding)
        
        tree_items.append({
            "path": rel_path,
            "mode": "100644",
            "type": "blob",
            "sha": blob_sha,
        })
        
        if (i + 1) % 100 == 0:
            print(f"Created blobs for {i + 1}/{len(files)} files...")
    
    print(f"Created {len(tree_items)} blobs total")
    
    # Create tree (may need to batch if too many items)
    # GitHub tree API limit is 1MB for the request, so we batch
    MAX_TREE_ITEMS = 500  # Conservative limit
    
    if len(tree_items) <= MAX_TREE_ITEMS:
        new_tree_sha = create_tree(base_tree_sha, tree_items)
    else:
        # Create intermediate trees
        print("Large file count, creating intermediate trees...")
        intermediate_trees = []
        for i in range(0, len(tree_items), MAX_TREE_ITEMS):
            batch = tree_items[i:i + MAX_TREE_ITEMS]
            tree_sha = create_tree(base_tree_sha, batch)
            intermediate_trees.append({"path": f"batch_{i // MAX_TREE_ITEMS}", "mode": "040000", "type": "tree", "sha": tree_sha})
        
        # Create final tree referencing intermediate trees
        new_tree_sha = create_tree(base_tree_sha, intermediate_trees)
    
    print(f"Created tree: {new_tree_sha}")
    
    # Create commit
    commit_sha = create_commit(COMMIT_MESSAGE, new_tree_sha, head_sha)
    print(f"Created commit: {commit_sha}")
    
    # Update ref
    update_ref(commit_sha)
    print(f"Updated {BRANCH} to {commit_sha}")
    print("Done!")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)

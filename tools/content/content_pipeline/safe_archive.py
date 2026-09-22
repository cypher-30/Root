"""Safe archive extraction for authorized local source archives.

Source archive readers enforce safe paths, size/count budgets, and refuse
symlinks or entries escaping the staging directory, whether the archive is
zip or tar-based. This is used only against explicitly authorized local
files supplied by the caller (e.g. an authorized Common Voice release
archive); this pipeline never downloads a full corpus archive automatically.
"""
from __future__ import annotations

import os
import tarfile
import zipfile
from pathlib import Path


class UnsafeArchiveMember(RuntimeError):
    pass


class ArchiveBudgetExceeded(RuntimeError):
    pass


def _is_within_directory(directory: Path, target: Path) -> bool:
    directory = directory.resolve()
    target = target.resolve()
    return directory == target or directory in target.parents


def safe_extract_zip(
    archive_path: Path,
    dest_dir: Path,
    max_total_bytes: int,
    max_entries: int = 100_000,
) -> list[Path]:
    dest_dir.mkdir(parents=True, exist_ok=True)
    extracted: list[Path] = []
    total = 0
    with zipfile.ZipFile(archive_path) as zf:
        infos = zf.infolist()
        if len(infos) > max_entries:
            raise ArchiveBudgetExceeded(
                f"{archive_path} has {len(infos)} entries, exceeding max_entries={max_entries}"
            )
        for info in infos:
            name = info.filename
            if name.startswith("/") or ".." in Path(name).parts or "\\" in name:
                raise UnsafeArchiveMember(f"Unsafe zip member path: {name}")
            target_path = dest_dir / name
            if not _is_within_directory(dest_dir, target_path):
                raise UnsafeArchiveMember(f"Zip member escapes destination: {name}")
            total += info.file_size
            if total > max_total_bytes:
                raise ArchiveBudgetExceeded(
                    f"Extracting {archive_path} would exceed max_total_bytes={max_total_bytes}"
                )
            if info.is_dir():
                target_path.mkdir(parents=True, exist_ok=True)
                continue
            target_path.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, open(target_path, "wb") as dst:
                dst.write(src.read())
            extracted.append(target_path)
    return extracted


def safe_extract_tar(
    archive_path: Path,
    dest_dir: Path,
    max_total_bytes: int,
    max_entries: int = 100_000,
) -> list[Path]:
    dest_dir.mkdir(parents=True, exist_ok=True)
    extracted: list[Path] = []
    total = 0
    with tarfile.open(archive_path) as tf:
        members = tf.getmembers()
        if len(members) > max_entries:
            raise ArchiveBudgetExceeded(
                f"{archive_path} has {len(members)} entries, exceeding max_entries={max_entries}"
            )
        for member in members:
            if member.issym() or member.islnk():
                raise UnsafeArchiveMember(f"Symlink/hardlink members are rejected: {member.name}")
            name = member.name
            if name.startswith("/") or ".." in Path(name).parts:
                raise UnsafeArchiveMember(f"Unsafe tar member path: {name}")
            target_path = dest_dir / name
            if not _is_within_directory(dest_dir, target_path):
                raise UnsafeArchiveMember(f"Tar member escapes destination: {name}")
            if member.isdir():
                target_path.mkdir(parents=True, exist_ok=True)
                continue
            total += member.size
            if total > max_total_bytes:
                raise ArchiveBudgetExceeded(
                    f"Extracting {archive_path} would exceed max_total_bytes={max_total_bytes}"
                )
            target_path.parent.mkdir(parents=True, exist_ok=True)
            src = tf.extractfile(member)
            if src is None:
                continue
            with open(target_path, "wb") as dst:
                dst.write(src.read())
            extracted.append(target_path)
    return extracted

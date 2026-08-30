#!/usr/bin/env python3
"""Encode and materialize the protected CPM E2E fixture without versioning its bytes."""

from __future__ import annotations

import argparse
import base64
import binascii
import gzip
import hashlib
import io
import os
import shlex
import sys
import tempfile
from collections.abc import Mapping, Sequence
from pathlib import Path


EXPECTED_SIZE = 134_115
EXPECTED_SHA256 = "2acd67e358456caf86aa0fad54f88b2e2fe0dfd2bc1160638b6f69b1689e1845"
SECRET_NAMES = tuple(f"QSM_E2E_CPM_FIXTURE_GZIP_B64_{index}" for index in range(1, 5))
MAX_SECRET_CHARS = 40_000


class FixtureSecretError(ValueError):
    """Raised when protected fixture material cannot be authenticated safely."""


def _validate_payload(
    payload: bytes,
    *,
    expected_size: int,
    expected_sha256: str,
) -> None:
    if len(payload) != expected_size:
        raise FixtureSecretError(
            f"CPM fixture size is {len(payload)}, expected {expected_size}"
        )
    actual_sha256 = hashlib.sha256(payload).hexdigest()
    if actual_sha256 != expected_sha256:
        raise FixtureSecretError(
            f"CPM fixture SHA-256 is {actual_sha256}, expected {expected_sha256}"
        )
    if not payload.startswith(b"\x53"):
        raise FixtureSecretError("CPM fixture has an invalid model header")


def encode_payload(
    payload: bytes,
    *,
    expected_size: int = EXPECTED_SIZE,
    expected_sha256: str = EXPECTED_SHA256,
) -> tuple[str, ...]:
    """Return four bounded gzip/Base64 chunks after authenticating the source fixture."""

    _validate_payload(
        payload,
        expected_size=expected_size,
        expected_sha256=expected_sha256,
    )
    compressed = gzip.compress(payload, compresslevel=9, mtime=0)
    encoded = base64.b64encode(compressed).decode("ascii")
    chunk_width = (len(encoded) + len(SECRET_NAMES) - 1) // len(SECRET_NAMES)
    if chunk_width > MAX_SECRET_CHARS:
        raise FixtureSecretError(
            "compressed CPM fixture does not fit in four bounded GitHub Actions secrets"
        )
    chunks = tuple(
        encoded[index * chunk_width : (index + 1) * chunk_width]
        for index in range(len(SECRET_NAMES))
    )
    if len(chunks) != len(SECRET_NAMES) or any(not chunk for chunk in chunks):
        raise FixtureSecretError("CPM fixture did not produce four non-empty secret chunks")
    return chunks


def decode_payload(
    chunks: Sequence[str],
    *,
    expected_size: int = EXPECTED_SIZE,
    expected_sha256: str = EXPECTED_SHA256,
) -> bytes:
    """Decode four bounded chunks and fail closed unless the exact fixture is recovered."""

    if len(chunks) != len(SECRET_NAMES):
        raise FixtureSecretError(f"expected exactly {len(SECRET_NAMES)} secret chunks")
    if any(not chunk or len(chunk) > MAX_SECRET_CHARS for chunk in chunks):
        raise FixtureSecretError("CPM fixture secret chunks are missing or oversized")
    try:
        compressed = base64.b64decode("".join(chunks), validate=True)
    except (binascii.Error, ValueError) as error:
        raise FixtureSecretError("CPM fixture secrets are not valid Base64") from error
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(compressed), mode="rb") as archive:
            payload = archive.read(expected_size + 1)
    except (EOFError, OSError) as error:
        raise FixtureSecretError("CPM fixture secrets are not a valid gzip stream") from error
    _validate_payload(
        payload,
        expected_size=expected_size,
        expected_sha256=expected_sha256,
    )
    return payload


def encode_fixture(source: Path, output_directory: Path) -> None:
    chunks = encode_payload(source.read_bytes())
    output_directory.mkdir(parents=True, exist_ok=True)
    for secret_name, chunk in zip(SECRET_NAMES, chunks, strict=True):
        destination = output_directory / secret_name
        if destination.is_symlink():
            raise FixtureSecretError(f"refusing to replace symlink {destination}")
        destination.write_text(chunk, encoding="ascii")
        destination.chmod(0o600)
        print(f"gh secret set {secret_name} < {shlex.quote(str(destination))}")


def materialize_fixture(output: Path, environment: Mapping[str, str]) -> None:
    missing = [name for name in SECRET_NAMES if not environment.get(name)]
    if missing:
        raise FixtureSecretError(
            "missing protected CPM fixture secrets: " + ", ".join(missing)
        )
    payload = decode_payload([environment[name] for name in SECRET_NAMES])
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.is_symlink():
        raise FixtureSecretError(f"refusing to replace symlink {output}")
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=output.parent,
            prefix=f".{output.name}.",
            delete=False,
        ) as temporary:
            temporary.write(payload)
            temporary_path = Path(temporary.name)
        temporary_path.chmod(0o600)
        os.replace(temporary_path, output)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    print(
        f"materialized authenticated CPM fixture ({EXPECTED_SIZE} bytes, "
        f"sha256={EXPECTED_SHA256})"
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    encode = commands.add_parser("encode", help="prepare four local secret-value files")
    encode.add_argument("--input", type=Path, required=True)
    encode.add_argument("--output-directory", type=Path, required=True)
    materialize = commands.add_parser(
        "materialize",
        help="reconstruct the fixture from the four protected environment variables",
    )
    materialize.add_argument("--output", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "encode":
            encode_fixture(args.input, args.output_directory)
        else:
            materialize_fixture(args.output, os.environ)
    except (FixtureSecretError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

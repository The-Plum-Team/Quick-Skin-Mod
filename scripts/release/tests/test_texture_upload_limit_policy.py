"""Source contracts of the per-texture upload limit and the legacy relay bound (#2055).

The networking module and the server runtime need a running Minecraft to execute, so these checks
pin the guards in their sources: each one fails when its guard is removed or reverted.
"""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

from scripts.architecture.source_inventory import java_source


ROOT = Path(__file__).resolve().parents[3]
CLIENT_NETWORK_HANDLER = java_source(
    "networking/ClientNetworkHandler.java", source_set="main", repository=ROOT
)
SEND_TEXTURE_PAYLOAD = java_source(
    "networking/payloads/SendTexturePayload.java", source_set="main", repository=ROOT
)
SERVER_NETWORK_HANDLERS = {
    source_set: java_source(
        "networking/ServerNetworkHandler.java", source_set=source_set, repository=ROOT
    )
    for source_set in ("main", "legacy1_20_1")
}
NETWORK_SYNC_SERVICE = java_source(
    "networking/NetworkSyncService.java", source_set="main", repository=ROOT
)
SERVER_RUNTIME = java_source("runtime/ServerRuntime.java", source_set="main", repository=ROOT)
LANG_DIRECTORY = ROOT / "common" / "src" / "main" / "resources" / "assets" / "quickskin" / "lang"
UPLOAD_TOO_LARGE_KEYS = (
    "quickskin.network.upload_too_large.skin",
    "quickskin.network.upload_too_large.cape",
)


def squash(text: str) -> str:
    """Collapses whitespace so a check survives reformatting but not a changed expression."""
    return re.sub(r"\s+", " ", text)


def region(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin : text.index(end, begin + len(start))]


class LegacyRelayDecodeBoundTest(unittest.TestCase):
    """A 2.x server relays each stored texture whole; decoding one must not end the session."""

    def test_the_pre_1_21_handler_decodes_up_to_the_vanilla_clientbound_limit(self) -> None:
        handler = squash(
            region(
                CLIENT_NETWORK_HANDLER.read_text(encoding="utf-8"),
                "public static void handleSendTexture(FriendlyByteBuf buf",
                "context.queue(",
            )
        )
        self.assertIn(
            "byte[] imageData = PacketHelper.readByteArray( buf,"
            " TextureTransferLimits.MAX_LEGACY_DIRECT_TEXTURE_BYTES);",
            handler,
        )
        self.assertNotIn("TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES", handler)
        # Both Stonecutter branches admit the relay through its own ingress entry.
        self.assertEqual(2, handler.count(".allowLegacyDirectTextureBytes("))
        self.assertNotIn(".allowWireBytes(", handler)

    def test_the_1_21_payload_decodes_up_to_the_vanilla_clientbound_limit(self) -> None:
        payload = squash(SEND_TEXTURE_PAYLOAD.read_text(encoding="utf-8"))
        decoder = payload[payload.index("buf -> {") :]
        self.assertIn(
            "byte[] imageData = PayloadCodecs.readByteArray( buf,"
            " TextureTransferLimits.MAX_LEGACY_DIRECT_TEXTURE_BYTES);",
            decoder,
        )
        self.assertNotIn("TextureTransferLimits.MAX_DIRECT_TEXTURE_BYTES", decoder)


class AnimationMetadataWriteBoundTest(unittest.TestCase):
    """Every metadata writer keeps the bound WirePayloadBudget assumes; readers stay tolerant."""

    def test_every_payload_codec_writes_within_the_metadata_bound(self) -> None:
        for name in (
            "UploadAnimationMetadataPayload",
            "UploadAnimationMetadataV2Payload",
            "SendAnimationMetadataPayload",
            "SendAnimationMetadataV2Payload",
        ):
            with self.subTest(payload=name):
                codec = squash(
                    java_source(
                        f"networking/payloads/{name}.java", source_set="main", repository=ROOT
                    ).read_text(encoding="utf-8")
                )
                writer = codec[codec.index("(buf, payload) -> {") : codec.index("buf -> new")]
                self.assertRegex(
                    writer,
                    r"PayloadCodecs\.writeString\(buf, payload\.metadataJson(\(\))?,"
                    r" TextureTransferLimits\.MAX_ANIMATION_METADATA_JSON_BYTES\);",
                )
                reader = codec[codec.index("buf -> new") :]
                self.assertIn("TextureTransferLimits.MAX_JSON_BYTES", reader)

    def test_every_friendly_byte_buf_writer_uses_the_bounded_helper(self) -> None:
        helper = squash(
            java_source(
                "networking/packets/PacketHelper.java", source_set="main", repository=ROOT
            ).read_text(encoding="utf-8")
        )
        self.assertIn(
            "buf.writeUtf(metadataJson, maximumBytes);", helper
        )
        self.assertIn(
            "int maximumBytes = TextureTransferLimits.MAX_ANIMATION_METADATA_JSON_BYTES;", helper
        )
        self.assertEqual(1, helper.count("writeUtf(metadataJson"))
        legacy = java_source(
            "networking/ModNetworking.java", source_set="legacy1_20_1", repository=ROOT
        ).read_text(encoding="utf-8")
        self.assertNotIn("writeUtf(metadataJson", legacy)
        self.assertEqual(3, legacy.count("PacketHelper.writeAnimationMetadata(buffer, metadataJson);"))


class ServerChunkLimitTest(unittest.TestCase):
    """The v1 chunk handler of every overlay bounds an upload by the session's profile."""

    def test_every_v1_chunk_handler_passes_the_profile_limits_to_the_assembler(self) -> None:
        for source_set, path in SERVER_NETWORK_HANDLERS.items():
            with self.subTest(source_set=source_set):
                text = path.read_text(encoding="utf-8")
                handler = squash(
                    region(
                        text,
                        "public static void handleTextureChunk(",
                        "submitTextureUpload(",
                    )
                )
                profile = handler.index(
                    "ProtocolProfile profile = ProtocolNetwork.profile(sender);"
                )
                add_chunk = handler.index("TextureChunkAssembler.getInstance().addChunk(")
                self.assertLess(profile, add_chunk)
                call = handler[add_chunk : handler.index(");", add_chunk) + 2]
                self.assertTrue(
                    call.endswith(
                        "chunkData, profile.maximumTextureBytes(),"
                        " profile.maximumChunkBytes());"
                    ),
                    call,
                )


class OversizedUploadTest(unittest.TestCase):
    """A texture over the session limit stays local; the rest of the appearance still syncs."""

    def setUp(self) -> None:
        self.source = NETWORK_SYNC_SERVICE.read_text(encoding="utf-8")
        self.prepare_upload = squash(
            region(self.source, "private PreparedUpload prepareUpload(", "private void reportOversizedUpload(")
        )

    def test_an_over_limit_texture_is_sent_with_an_empty_id_and_reported(self) -> None:
        over_limit = self.prepare_upload[
            self.prepare_upload.index("if (textureData.length > uploadLimit) {") :
            self.prepare_upload.index("String networkHash")
        ]
        self.assertIn(
            "reportOversizedUpload(textureType, textureData.length, uploadLimit, protocolProfile);",
            over_limit,
        )
        self.assertIn(
            "return new PreparedUpload( key, null, textureType, new byte[0][], true,"
            " protocolProfile);",
            over_limit,
        )
        # A negotiated session used to return null here, which retried forever.
        self.assertNotIn("return null;", over_limit)
        self.assertIn("int uploadLimit = protocolProfile.maximumUploadBytes();", self.prepare_upload)

    def test_a_texture_too_large_to_produce_is_reported_instead_of_retried(self) -> None:
        unavailable = self.prepare_upload[
            self.prepare_upload.index("if (textureData == null) {") :
            self.prepare_upload.index("if (textureData.length > uploadLimit) {")
        ]
        self.assertEqual(1, self.prepare_upload.count("if (textureData == null)"))
        self.assertIn("if (sizeHint <= uploadLimit) return null;", unavailable)
        self.assertIn(
            "reportOversizedUpload(textureType, sizeHint, uploadLimit, protocolProfile);",
            unavailable,
        )
        self.assertIn(
            "return new PreparedUpload( key, null, textureType, new byte[0][], true,"
            " protocolProfile);",
            unavailable,
        )

    def test_an_empty_network_id_keeps_the_rest_of_the_appearance(self) -> None:
        prepare_sync = squash(
            region(self.source, "private PreparedSync prepareSync(", "private PreparedUpload prepareUpload(")
        )
        self.assertIn(
            'serverSkinId = upload.networkHash == null ? "" : "local_skin:" + upload.networkHash;',
            prepare_sync,
        )
        self.assertIn(
            'serverCapeId = upload.networkHash == null ? "" : "local_cape:" + upload.networkHash;',
            prepare_sync,
        )

    def test_the_report_is_recorded_only_when_the_chat_message_is_shown(self) -> None:
        report = squash(
            region(self.source, "private void reportOversizedUpload(", "private synchronized void enqueuePreparedSync(")
        )
        queued = report[report.index("minecraft.execute(() -> {") :]
        before = report[: report.index("minecraft.execute(() -> {")]
        self.assertNotIn("reportedOversizedUploads.add(", before)
        self.assertIn(
            "if (minecraft.player == null || reportedOversizedUploads.size() >="
            " MAX_REPORTED_OVERSIZED_UPLOADS || !reportedOversizedUploads.add(reportKey)) return;",
            queued,
        )
        self.assertLess(
            queued.index("!reportedOversizedUploads.add(reportKey)"),
            queued.index("Component.translatable("),
        )
        for key in UPLOAD_TOO_LARGE_KEYS:
            self.assertIn(f'"{key}"', report)

    def test_every_locale_translates_the_rejection_message(self) -> None:
        english = json.loads((LANG_DIRECTORY / "en_us.json").read_text(encoding="utf-8"))
        english_keys = list(english)
        locales = sorted(path for path in LANG_DIRECTORY.glob("*.json") if path.name != "en_us.json")
        self.assertEqual(24, len(locales))
        for path in locales:
            with self.subTest(locale=path.name):
                translated = json.loads(path.read_text(encoding="utf-8"))
                for key in UPLOAD_TOO_LARGE_KEYS:
                    self.assertTrue(key in translated, f"{path.name} lacks {key}")
                    self.assertNotEqual(english[key], translated[key])
                    self.assertEqual(
                        re.findall(r"%(?:\d+\$)?[sd%]", english[key]),
                        re.findall(r"%(?:\d+\$)?[sd%]", translated[key]),
                    )
                # In en_us order: the section closes every file, as it closes en_us.
                self.assertEqual(english_keys[-3:], list(translated)[-3:])


class ServerRuntimeOrderTest(unittest.TestCase):
    """The limit reaches the protocol and the texture cache before any stored texture loads."""

    def test_the_limit_is_configured_before_the_texture_cache_loads(self) -> None:
        start = squash(
            region(
                SERVER_RUNTIME.read_text(encoding="utf-8"),
                "public synchronized void start(MinecraftServer server) {",
                "public synchronized void prepareStop(",
            )
        )
        order = [
            "ServerConfig.reload();",
            "int uploadLimit = ProtocolSessions.getInstance().configureServerUploadLimit("
            " ServerConfig.getInstance().maxTextureUploadBytes());",
            "textureCache.configureUploadLimit(uploadLimit);",
            "textureCache.init(worldPath);",
            "appearanceStorage.init(worldPath);",
        ]
        positions = [start.index(step) for step in order]
        self.assertEqual(sorted(positions), positions)

    def test_a_lowered_limit_warns_about_released_clients(self) -> None:
        start = squash(
            region(
                SERVER_RUNTIME.read_text(encoding="utf-8"),
                "public synchronized void start(MinecraftServer server) {",
                "public synchronized void prepareStop(",
            )
        )
        guard = "if (uploadLimit < TextureTransferLimits.DEFAULT_SERVER_UPLOAD_BYTES) {"
        self.assertIn(guard, start)
        warning = start[start.index(guard) :]
        self.assertIn("QuickSkinInfo.LOGGER.warn(", warning)
        self.assertIn("Quick Skin 3.1.0 or older", warning)
        self.assertIn("will not sync their appearance", warning)


if __name__ == "__main__":
    unittest.main()

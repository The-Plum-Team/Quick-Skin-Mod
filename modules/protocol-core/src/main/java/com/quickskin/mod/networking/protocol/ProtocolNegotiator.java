package com.quickskin.mod.networking.protocol;

import com.quickskin.mod.networking.TextureTransferLimits;

import java.util.Objects;

/** Pure fail-closed negotiation policy shared by client, server, and contract tests. */
public final class ProtocolNegotiator {
    public static final int CURRENT_VERSION = 2;

    private ProtocolNegotiator() {
    }

    public static ProtocolProfile negotiate(Policy local, ProtocolOffer peer) {
        Objects.requireNonNull(local, "local");
        if (peer == null || !peer.isStructurallyValid()) {
            return ProtocolProfile.incompatible("malformed-offer");
        }

        int selectedVersion = Math.min(local.maximumVersion(), peer.maximumVersion());
        int minimumCompatible = Math.max(local.minimumVersion(), peer.minimumVersion());
        if (selectedVersion < minimumCompatible) {
            return ProtocolProfile.incompatible("no-common-version");
        }

        long negotiatedCapabilities = local.capabilityMask()
                & peer.capabilityMask()
                & ProtocolCapability.knownMask();
        if ((negotiatedCapabilities & local.requiredCapabilityMask())
                != local.requiredCapabilityMask()) {
            return ProtocolProfile.incompatible("missing-required-capability");
        }

        int textureLimit = Math.min(local.maximumTextureBytes(), peer.maximumTextureBytes());
        int chunkLimit = Math.min(local.maximumChunkBytes(), peer.maximumChunkBytes());
        chunkLimit = Math.min(chunkLimit, textureLimit);
        long representableTextureBytes = (long) chunkLimit * TextureTransferLimits.MAX_CHUNKS;
        textureLimit = (int) Math.min((long) textureLimit, representableTextureBytes);
        if (textureLimit < 1 || chunkLimit < 1) {
            return ProtocolProfile.incompatible("invalid-negotiated-limit");
        }

        return new ProtocolProfile(
                ProtocolProfile.Mode.NEGOTIATED,
                selectedVersion,
                negotiatedCapabilities,
                textureLimit,
                chunkLimit,
                "negotiated");
    }

    /** Verifies that an untrusted acknowledgement only narrows the initiating local offer. */
    public static ProtocolProfile verifyAcknowledgement(
            Policy local, ProtocolAcknowledgement acknowledgement) {
        Objects.requireNonNull(local, "local");
        if (acknowledgement == null || !acknowledgement.accepted()) {
            return ProtocolProfile.incompatible("peer-rejected");
        }
        ProtocolOffer selected = new ProtocolOffer(
                acknowledgement.selectedVersion(),
                acknowledgement.selectedVersion(),
                acknowledgement.capabilityMask(),
                acknowledgement.maximumTextureBytes(),
                acknowledgement.maximumChunkBytes());
        ProtocolProfile verified = negotiate(local, selected);
        if (!verified.negotiated()
                || verified.version() != acknowledgement.selectedVersion()
                || verified.capabilityMask() != acknowledgement.capabilityMask()
                || verified.maximumTextureBytes() != acknowledgement.maximumTextureBytes()
                || verified.maximumChunkBytes() != acknowledgement.maximumChunkBytes()) {
            return ProtocolProfile.incompatible("invalid-acknowledgement");
        }
        return verified;
    }

    /** Local hard policy. Remote values can only reduce these limits and capabilities. */
    public record Policy(
            int minimumVersion,
            int maximumVersion,
            long capabilityMask,
            long requiredCapabilityMask,
            int maximumTextureBytes,
            int maximumChunkBytes
    ) {
        public Policy {
            if (minimumVersion < 1 || maximumVersion < minimumVersion) {
                throw new IllegalArgumentException("Invalid local protocol range");
            }
            if ((requiredCapabilityMask & capabilityMask) != requiredCapabilityMask) {
                throw new IllegalArgumentException("Required capabilities must be advertised locally");
            }
            if ((capabilityMask & ~ProtocolCapability.knownMask()) != 0L) {
                throw new IllegalArgumentException("Local policy contains unknown capabilities");
            }
            if (maximumTextureBytes < 1 || maximumChunkBytes < 1
                    || maximumChunkBytes > maximumTextureBytes) {
                throw new IllegalArgumentException("Invalid local protocol limits");
            }
        }

        public ProtocolOffer offer() {
            return new ProtocolOffer(
                    minimumVersion,
                    maximumVersion,
                    capabilityMask,
                    maximumTextureBytes,
                    maximumChunkBytes);
        }
    }
}

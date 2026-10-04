Customizable Player Models (CPM) embedded-model fixtures for CpmEmbeddedSkinImportTest.

CPM can store a model definition inside the pixels of a 64x64 skin. Its free-space template marks
the data texels: in the template, the model's channel (green for classic, red for slim) is 0xFF,
and the blue byte says whether the texel holds three bytes (blue 0xFF: B, G, R, always alpha 0xFF)
or four bytes (B, G, R, then A). Bytes are written row-major, from (0,0) to (63,63).

Files
- free-space-classic.txt, free-space-slim.txt: 64 lines of 64 characters, one per texel.
  '.' is not a data texel, '3' a three-byte texel and '4' a four-byte texel. They record facts
  about the format (derived from CPM's free_space_template.png); no CPM asset is copied.
  Capacity: 2912 bytes classic, 3360 bytes slim.
- cpm-embedded-full.png / .payload.bin: a skin carrying a real 106-byte classic model definition
  (headless player with re-posed limbs), written like CPM's Exporter.exportSkin0. RGBA PNG.
- cpm-embedded-capacity-classic.png / .payload.bin: the same base skin filled to the full 2912-byte
  classic capacity with java.util.Random(0x51C0FFEEL) bytes, so four-byte texels carry every kind of
  alpha including 0. Texel (4,20), outside CPM's free space and inside vanilla's opaque base layer,
  is fully transparent so flattening stays observable.

Generation (offline, outside the build; nothing here runs CPM in tests)
- CPM jar: CustomPlayerModels-1.20-0.6.27a.jar,
  sha256 f4964fb5b008eea1401853c6ba19a623b5fd6f10e3e14c77d686d999a0bb7c42.
- free_space_template.png from that jar: sha256
  f7648335d68832b76d29c8dc13ef300dc4784066278ff0d87f4ce00a92c302bb.
- cpm-embedded-full.png: `CpmEmbedRepro make <template> <base> <out.png> <payload.bin>`, which
  writes HEADER 0x53 then a checksummed SKIN_TYPE, definition and END block sequence through
  com.tom.cpm.shared.io.SkinDataOutputStream (channel SkinType.DEFAULT.getChannel() = 1).
- The capacity fixture and the two maps: `CpmFixtureGen <template> <base> <outdir>`, which uses the
  same SkinDataOutputStream for the payload and reads the template for the maps.
- CPM's own reader (SkinDataInputStream plus ModelDefinitionLoader's sequence) loads
  cpm-embedded-full.png with checksum OK and returns exactly cpm-embedded-full.payload.bin.

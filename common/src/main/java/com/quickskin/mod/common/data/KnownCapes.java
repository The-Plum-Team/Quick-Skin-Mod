package com.quickskin.mod.common.data;

import com.quickskin.mod.platform.QuickSkinInfo;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

import java.util.Locale;

/**
 * Enum containing all known official Minecraft capes, sorted from most common to rarest.
 * Each cape has a corresponding texture file in assets/quickskin/textures/capes/
 */
public enum KnownCapes {
    // --- Special Option ---
    // Functional entry to remove the player's cape.
    NONE("No Cape", "__NONE__", "Remove cape", false),

    // --- Custom Mod Capes ---
    RICKROLL("Rickroll Cape", "rickroll", "Never gonna give you up", true, true),
    ANGEL("Angel Cape", "angel", "A custom mod cape", true),
    AXOLOTL("Axolotl Cape", "axolotl", "A custom mod cape", true),
    BMO("BMO Cape", "bmo", "Or Football?!", true),
    CAT("Cat Cape", "cat", "A custom mod cape", true),
    DEMON("Demon Cape", "demon", "A custom mod cape", true),
    FOX("Fox Cape", "fox", "A custom mod cape", true),
    GLASS("Glass Cape", "glass", "A custom mod cape", true),
    HEART("Heart Cape", "heart", "A custom mod cape", true),
    INTERROGATION("Interrogation Cape", "interrogation", "A custom mod cape", true),
    NETHER_PORTAL("Nether Portal Cape", "nether_portal", "A custom mod cape", true),
    OLD("Old Cape", "old", "A custom mod cape", true),
    BURNOUT("Burnout Cape", "burnout", "A custom mod cape", true),
    STRAWBERRY("Strawberry Cape", "strawberry", "A custom mod cape", true),
    WAFFLE("Waffle Cape", "waffle", "A custom mod cape", true),
    WARDEN("Warden Cape", "warden", "A custom mod cape", true),

    // --- Common (500,001+ owners) ---
    // Capes owned by millions of players.
    MIGRATOR("Migrator Cape", "migrator", "Red cape with gold trim", false),
    ANNIVERSARY_15TH("15th Anniversary Cape", "15th_anniversary", "Green creeper cape with '15'", false),
    FOLLOWERS("Follower's Cape", "followers", "TikTok cape", false),
    VANILLA("Vanilla Cape", "vanilla", "Orange cape for Java+Bedrock owners", false),
    PAN("Pan Cape", "pan", "Pancake cape for all Bedrock players", false),

    // --- Uncommon (50,001 - 500,000 owners) ---
    // Capes from more recent events or promotions, some still obtainable.
    CHERRY_BLOSSOM("Cherry Blossom Cape", "cherry_blossom", "Pink cape from 2023 mob vote", false),
    PURPLE_HEART("Purple Heart Cape", "purple_heart", "Twitch cape", false),
    PRIDE("Pride Cape", "pride", "2023 Pride celebration cape", false),
    COMMON("Common Cape", "common", "Gray cape", false),
    MENACE("Menace Cape", "menace", "Purple cape", false),
    MOJANG_OFFICE("Mojang Office Cape", "mojang_office", "Office cape", false),
    HOME("Home Cape", "home", "Light blue cape", false),
    YEARN("Yearn Cape", "yearn", "Gray cape", false),
    MCC_15TH_YEAR("MCC 15th Year", "mcc_15th", "Minecraft Championship cape", false),
    COPPER("Copper Cape", "copper", "New official copper-themed cape", false),

    // --- Rare (1,001 - 50,000 owners) ---
    // Capes from early MineCons or special events. No longer obtainable.
    FOUNDERS("Founder's Cape", "founders", "Minecon 2019 cape", false),
    XBOX("Xbox Cape", "xbox", "Xbox Game Studios anniversary cape", false),
    MINECRAFT_EXPERIENCE("Experience Cape", "experience", "Minecraft Experience event", false),
    MINECON_2016("MineCon 2016", "minecon_2016", "Enderman cape", false),
    MINECON_2015("MineCon 2015", "minecon_2015", "Iron golem cape", false),
    MINECON_2013("MineCon 2013", "minecon_2013", "Green piston cape", false),
    MINECON_2012("MineCon 2012", "minecon_2012", "Blue pickaxe cape", false),
    MINECON_2011("MineCon 2011", "minecon_2011", "Red creeper cape", false),

    // --- Epic (101-1,000 owners) ---
    // Very rare capes, typically for Mojang staff or contributors.
    REALMS_MAPMAKER("Realms Mapmaker", "realms", "For Realms content creators", false),
    MOJANG("Mojang Cape", "mojang", "Classic Mojang employee cape", false),
    MOJANG_STUDIOS("Mojang Studios Cape", "mojang_studios", "New Mojang Studios cape", false),

    // --- Legendary (1-100 owners) ---
    // The absolute rarest capes, mostly given to single individuals.
    TRANSLATOR("Translator Cape", "translator", "For translation contributors", false),
    MOJIRA_MODERATOR("Mojira Moderator", "mojira", "Bug tracker moderators", false),
    MOJANG_CLASSIC("Mojang (Classic)", "mojang_classic", "Old Mojang cape", false),
    COBALT("Cobalt Cape", "cobalt", "Cobalt game champion", false),
    SCROLLS("Scrolls Cape", "scrolls", "Scrolls game champion", false),
    TURTLE("Turtle Cape", "turtle", "Personal cape", false),
    VALENTINE("Valentine Cape", "valentine", "Valentine's cape", false),
    TEST("Test Cape", "test", "Test cape", false),
    MILLIONTH_CUSTOMER("Millionth Customer", "millionth", "1,000,000th customer cape", false),
    PRISMARINE("Prismarine Cape", "prismarine", "Personal cape", false),
    SNOWMAN("Snowman Cape", "snowman", "Ray Cokes cape", false),
    SPADE("Spade Cape", "spade", "Personal cape", false),
    BIRTHDAY("Birthday Cape", "birthday", "Birthday cape", false),
    DB("dB Cape", "db", "Personal cape", false),
    OXEYE("Oxeye Cape", "oxeye", "Personal cape", false),
    BACON("Bacon Cape", "bacon", "Personal cape for Miclee", false),
    CUBED("Cubed Cape", "cubed", "Personal cape for Dr. Cubed", false),
    FROG("Frog Cape", "frog", "Personal cape for Grumm", false),
    SNAIL("Snail Cape", "snail", "Personal cape for Bile", false);

    private final String displayName;
    private final String id;
    private final String description;
    //? if <1.21.11 {
    private final ResourceLocation textureLocation;
    //?} else {
    private final Identifier textureLocation;
    //?}
    private final boolean isCustom;
    private final boolean isAnimated;

    KnownCapes(String displayName, String id, String description, boolean isCustom) {
        this(displayName, id, description, isCustom, false);
    }

    KnownCapes(String displayName, String id, String description, boolean isCustom, boolean isAnimated) {
        this.displayName = displayName;
        this.id = id;
        this.description = description;
        this.isCustom = isCustom;
        this.isAnimated = isAnimated;

        // Generate texture location from ID
        // Textures should be placed in: assets/quickskin/textures/capes/
        this.textureLocation = id.equals("__NONE__") ? null :
                //? if <1.21.11 {
                    //? if <1.21 {
                new ResourceLocation(QuickSkinInfo.MOD_ID, "textures/capes/" + id + ".png");
                    //?} else {
                ResourceLocation.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "textures/capes/" + id + ".png");
                    //?}
                //?} else {
                Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "textures/capes/" + id + ".png");
                //?}
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Get the resource location for this cape's texture.
     * @return Identifier or null for NONE cape
     */
    //? if <1.21.11 {
    public ResourceLocation getTextureLocation() {
    //?} else {
    public Identifier getTextureLocation() {
    //?}
        return textureLocation;
    }

    /**
     * Get a cape by its ID
     */
    public static KnownCapes getById(String id) {
        for (KnownCapes cape : values()) {
            if (cape.getId().equals(id != null ? id.toLowerCase(Locale.ROOT) : null)) {
                return cape;
            }
        }
        return null;
    }

    /**
     * Check if this represents a special "no cape" option
     */
    public boolean isNoCape() {
        return this == NONE;
    }

    public boolean isCustom() {
        return isCustom;
    }

    public boolean isAnimated() {
        return isAnimated;
    }
}

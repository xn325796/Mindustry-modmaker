package dev.modmaker.core.fmt;

import com.google.gson.JsonObject;
import dev.modmaker.core.json.HjsonReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The format rules that decide how names, folders, sprite regions and bundles are shaped. */
class FmtRulesTest {

    @Test
    void internalNameFollowsModMetaCleanup() {
        assertEquals("greg-industry", NameRules.internalName("Greg Industry"));
        assertEquals("社会主义工业化", NameRules.internalName("社会主义工业化"));
        assertEquals("", NameRules.internalName(null));
    }

    @Test
    void internalNameStripsColorTagsBeforeFolding() {
        // Mods.ModMeta.cleanup strips colours first (Mods.java:1428) and lowercases afterwards
        // (Mods.java:1435). Without the strip step the prefix - and with it every sprite region,
        // bundle key and reference - disagreed with the game for names carrying a colour tag.
        assertEquals("foo-mod", NameRules.internalName("[red]Foo Mod"));
        assertEquals("foo", NameRules.internalName("[red]Foo"));
        assertEquals("foo", NameRules.internalName("[RED]Foo"), "arc registers the upper-case spelling");
        // arc registers only the lower-case and CONSTANT_CASE spellings, so "[Red]" is ordinary text
        // and survives - lower cased like any other character - into the internal name.
        assertEquals("[red]foo", NameRules.internalName("[Red]Foo"));
        assertEquals("[accent]foo", NameRules.internalName("[accent]Foo"), "accent is not a colour");
        assertEquals("foo", NameRules.internalName("[#ff0000]Foo"));
        assertEquals("foo", NameRules.internalName("[]Foo"));
        assertEquals("[]foo", NameRules.internalName("[[red]]Foo"));
        assertEquals("foo[", NameRules.internalName("[red]Foo["), "an unclosed bracket is text");
        assertEquals("foo-bar", NameRules.internalName("Foo [red]Bar"));
        assertEquals("exoprosopa", NameRules.internalName("[scarlet]Exoprosopa"));
        assertEquals("", NameRules.internalName("[red]"));
        // Multi-word colours: the lower-case spelling drops the underscore, the constant keeps it.
        assertEquals("foo", NameRules.internalName("[darkgray]Foo"));
        assertEquals("foo", NameRules.internalName("[DARK_GRAY]Foo"));
        assertEquals("[dark_gray]foo", NameRules.internalName("[dark_gray]Foo"));
    }

    @Test
    void contentNamesArePrefixedWithTheInternalName() {
        assertEquals("my-mod-kiln", NameRules.fullName("my-mod", "kiln"));
        assertTrue(NameRules.isValidContentName("kiln-2"));
        assertFalse(NameRules.isValidContentName("kiln 2"));
    }

    @Test
    void aPatchKeepsTheNameItPatches() {
        // ContentParser hand the existing content back for a patch, and readBundle keys its strings
        // off that content's own name - so a patch of vanilla "router" owns "block.router.name",
        // not "block.my-mod-router.name".
        assertEquals("router", NameRules.fullName("my-mod", "router", true));
        assertEquals("block.router.name", NameRules.bundleKey(ContentType.block,
            NameRules.fullName("my-mod", "router", true), "name"));
        assertEquals("block.my-mod-router.name", NameRules.bundleKey(ContentType.block,
            NameRules.fullName("my-mod", "router", false), "name"),
            "new content keeps the mod prefix");
    }

    @Test
    void spriteRegionsMatchTheGamesPrefixingRule() {
        // sprites/kiln.png -> my-mod-kiln
        assertEquals("my-mod-kiln", NameRules.spriteRegion("my-mod", "kiln", true));
        // Quirk of Mods.java:404-412: the "already prefixed" test only matches category prefixes,
        // so a file that starts with the mod name itself gets prefixed twice.
        assertEquals("my-mod-my-mod-kiln", NameRules.spriteRegion("my-mod", "my-mod-kiln", true));
        // category prefix wins over the mod prefix (the `block-my-mod-x-full` case)
        assertEquals("block-my-mod-kiln-full", NameRules.spriteRegion("my-mod", "block-my-mod-kiln-full", true));
        // sprites-override/ is never prefixed
        assertEquals("ui-button", NameRules.spriteRegion("my-mod", "ui-button", false));
        // dots are kept in the packed name; only the lookup check strips them
        assertEquals("my-mod-kiln.alt", NameRules.spriteRegion("my-mod", "kiln.alt", true));
        assertEquals("kiln", NameRules.regionLookupName("kiln.alt"));
    }

    @Test
    void onlyNineContentTypesCanBeDeclaredInFiles() {
        assertEquals(9, ContentType.creatable().size());
        assertTrue(ContentType.item.fileBacked());
        assertFalse(ContentType.bullet.fileBacked(), "bullets have no content parser and must stay inline");
        assertFalse(ContentType.unitCommand.fileBacked());
    }

    @Test
    void folderAliasesCoverTheLegacyPluralNames() {
        assertEquals(Set.of("status", "statuses"), ContentType.status.acceptedFolders());
        assertEquals(Set.of("weathers", "weather"), ContentType.weather.acceptedFolders());
        assertEquals(Set.of("items"), ContentType.item.acceptedFolders());
        assertEquals(ContentType.status, ContentType.fromFolder("status"));
        assertEquals(ContentType.weather, ContentType.fromFolder("weathers"));
        assertEquals(ContentType.unitStance, ContentType.fromFolder("unitStances"));
        assertNull(ContentType.fromFolder("nonsense"));
    }

    @Test
    void bundleFilesAndLocales() {
        assertTrue(BundleCodec.isBundleFile("bundle.properties"));
        assertTrue(BundleCodec.isBundleFile("bundle_zh_CN.properties"));
        // The dash-wrapped name seen in real mods does not start with "bundle" and is ignored by the game.
        assertFalse(BundleCodec.isBundleFile("---bundle.properties---"));
        assertEquals("", BundleCodec.localeOf("bundle.properties"));
        assertEquals("zh_CN", BundleCodec.localeOf("bundle_zh_CN.properties"));
        assertEquals("bundle_zh_CN.properties", BundleCodec.fileNameOf("zh_CN"));
        assertEquals("bundle.properties", BundleCodec.fileNameOf(""));
        // English is the game's base bundle, not bundle_en.properties.
        assertEquals("bundle.properties", BundleCodec.fileNameOf("en"));
    }

    @Test
    void parsesManagedBundleKeysOnly() {
        BundleCodec.ContentKey key = BundleCodec.parseKey("block.my-mod-kiln.name");
        assertNotNull(key);
        assertEquals(ContentType.block, key.type());
        assertEquals("my-mod-kiln", key.fullName());
        assertEquals("name", key.field());
        assertEquals("kiln", BundleCodec.contentNameOf(key.fullName(), "my-mod"));

        assertNull(BundleCodec.parseKey("category.条件"));
        assertNull(BundleCodec.parseKey("stat.条件"));
        assertNull(BundleCodec.parseKey("mod.my-mod.name"));
        assertNull(BundleCodec.parseKey("block.my-mod-kiln.tooltip"));
        // Bullets are not file backed, so their keys stay in the extra table.
        assertNull(BundleCodec.parseKey("bullet.my-mod-shell.name"));
    }

    @Test
    void readsResearchValues() {
        JsonObject tree = HjsonReader.readObject("""
            { "a": "copper", "b": { "parent": "my-mod-root", "requirements": [] },
              "c": { "root": true, "name": "techtree.x" }, "d": {} }
            """);
        assertEquals("copper", TechTreeRules.parent(tree.get("a")));
        assertEquals("my-mod-root", TechTreeRules.parent(tree.get("b")));
        assertTrue(TechTreeRules.isRoot(tree.get("c")));
        assertNull(TechTreeRules.parent(tree.get("c")));
        assertTrue(TechTreeRules.isWellFormed(tree.get("a")));
        assertFalse(TechTreeRules.isWellFormed(tree.get("d")), "a node without parent or root is dropped by the game");
    }

    @Test
    void researchRoundTripsThroughReadAndWrite() {
        JsonObject tree = HjsonReader.readObject("""
            { "a": "copper",
              "b": { "parent": "my-mod-root", "planet": "serpulo",
                     "requirements": ["copper/10", { "item": "lead", "amount": 5 }],
                     "objectives": [ { "type": "SectorComplete", "preset": "groundZero" } ] },
              "c": { "root": true, "name": "techtree.x", "requiresUnlock": true } }
            """);

        TechTreeRules.Node plain = TechTreeRules.read(tree.get("a"));
        assertEquals("copper", plain.parent());
        // A node that only names a parent stays the bare string the game's first branch reads.
        assertTrue(TechTreeRules.write(plain).isJsonPrimitive());
        assertEquals("copper", TechTreeRules.write(plain).getAsString());

        TechTreeRules.Node rich = TechTreeRules.read(tree.get("b"));
        assertEquals("my-mod-root", rich.parent());
        assertEquals("serpulo", rich.planet());
        assertEquals(List.of("copper/10", "lead/5"), rich.requirements(),
            "both accepted requirement shapes read back as item/amount");
        assertEquals(1, rich.objectives().size());
        assertEquals(TechTreeRules.write(rich),
            TechTreeRules.write(TechTreeRules.read(TechTreeRules.write(rich))),
            "write -> read -> write must be stable");

        TechTreeRules.Node root = TechTreeRules.read(tree.get("c"));
        assertTrue(root.root());
        assertEquals("techtree.x", root.name());
        assertTrue(root.requiresUnlock());
        assertNull(root.parent(), "the game ignores parent when root is set");
        assertTrue(TechTreeRules.write(root).getAsJsonObject().has("root"));
    }

    @Test
    void objectiveTypesAndTargetKeysMatchTheGameClasses() {
        assertEquals(List.of("Research", "Produce", "SectorComplete", "OnSector", "OnPlanet"),
            TechTreeRules.OBJECTIVE_TYPES);
        assertEquals("content", TechTreeRules.objectiveTargetKey("Produce"));
        assertEquals("preset", TechTreeRules.objectiveTargetKey("SectorComplete"));
        assertEquals("planet", TechTreeRules.objectiveTargetKey("OnPlanet"));

        JsonObject objective = TechTreeRules.objective("SectorComplete", "groundZero");
        assertEquals("SectorComplete", objective.get("type").getAsString());
        assertEquals("groundZero", objective.get("preset").getAsString());
    }

    @Test
    void anEmptyNodeWritesNothingAndPatchesMayNotCarryResearch() {
        assertNull(TechTreeRules.write(TechTreeRules.Node.empty()));
        assertNull(TechTreeRules.write(TechTreeRules.Node.empty().withRoot(false)));
        assertTrue(TechTreeRules.allowsResearch(false));
        assertFalse(TechTreeRules.allowsResearch(true), "a patch may not modify the tech tree");
    }

    @Test
    void propertiesRoundTripRealWorldShapes() {
        String text = """
            # a comment
            block.rtg-generator.name = RTG
            item.pyratite.name=硫
            long.value = one \\
              two
            semi\\:colon = value\\:with\\:colons
            """;
        PropertiesFile file = PropertiesFile.parse(text);
        assertEquals("RTG", file.values.get("block.rtg-generator.name"));
        assertEquals("硫", file.values.get("item.pyratite.name"));
        assertEquals("one two", file.values.get("long.value"));
        assertEquals("value:with:colons", file.values.get("semi:colon"));

        PropertiesFile reparsed = PropertiesFile.parse(file.write());
        assertEquals(file.values, reparsed.values);
    }

    @Test
    void aCommentLineIsNeverContinued() {
        // java.util.Properties and the game's PropertiesUtils end a logical line at a comment: a
        // trailing backslash there is literal text, so the key on the next line still exists in
        // game - it used to be swallowed into the comment and then re-appended as a duplicate.
        String text = "# see docs at C:\\\nkey=value\n";
        PropertiesFile file = PropertiesFile.parse(text);
        assertEquals("value", file.values.get("key"));
        assertEquals(text, file.write(), "nothing was edited, so the bytes come back as they are");
    }

    @Test
    void anInvalidUnicodeEscapeKeepsItsBackslash() {
        // The escape stays literal text; dropping the backslash silently changed the value.
        PropertiesFile file = PropertiesFile.parse("a=\\uZZZZ\n");
        assertEquals("\\uZZZZ", file.values.get("a"));
        assertFalse(file.problems.isEmpty(), "the unfixable escape is still reported");
    }

    @Test
    void propertiesRoundTripKeepsTheDocumentAsItIs() {
        // A bundle is a hand-edited document: comments, blank lines, separators, indentation, CRLF
        // and even the continuation escape must come back untouched when nothing was edited.
        String text = "\uFEFF# Kit bundle\r\n\r\n# names\r\nblock.kiln.name = Kiln\r\n"
            + "item.x.name: X\n   spaced.key   =   value   \n"
            + "long.value = one \\\n  two\nlast.key = v";
        assertEquals(text, PropertiesFile.parse(text).write());
    }

    @Test
    void editingOneValueRewritesOnlyItsLine() {
        String text = "# comment\n\nblock.kiln.name = Kiln\nblock.smelter.name = Smelter\n";
        PropertiesFile file = PropertiesFile.parse(text);
        file.put("block.kiln.name", "Kiln II");

        assertEquals("# comment\n\nblock.kiln.name = Kiln II\nblock.smelter.name = Smelter\n",
            file.write(), "the comment, the blank line and the untouched key keep their bytes");
    }

    @Test
    void anEditedLineKeepsItsSeparatorSpacingAndLineEnding() {
        PropertiesFile colon = PropertiesFile.parse("item.x.name: X\r\n");
        colon.put("item.x.name", "Y");
        assertEquals("item.x.name: Y\r\n", colon.write());

        PropertiesFile padded = PropertiesFile.parse("item.x.name    =    X\n");
        padded.put("item.x.name", "Y");
        assertEquals("item.x.name    =    Y\n", padded.write());

        PropertiesFile bare = PropertiesFile.parse("item.x.name\n");
        bare.put("item.x.name", "Y");
        assertEquals("item.x.name=Y\n", bare.write(), "a bare key gains the separator it lacked");

        PropertiesFile needing = PropertiesFile.parse("a = x\n");
        needing.put("a", "one\ttwo");
        assertEquals("a = one\\ttwo\n", needing.write(), "values are still escaped structurally");
    }

    @Test
    void newKeysAreAppendedAndRemovedKeysLoseTheirLine() {
        PropertiesFile file = PropertiesFile.parse("# keep me\nblock.kiln.name = Kiln");
        file.put("block.smelter.name", "Smelter");
        file.remove("block.kiln.name");

        // Appended keys keep the format the tool has always written; the removed key's line is gone
        // and the missing final break is added so the new key is not glued onto the comment.
        assertEquals("# keep me\nblock.smelter.name=Smelter\n", file.write());
    }

    @Test
    void aDuplicatedKeyRewritesTheDefinitionTheGameUses() {
        PropertiesFile file = PropertiesFile.parse("a=first\na=second\n");
        file.put("a", "third");
        assertEquals("a=first\na=third\n", file.write(),
            "the game keeps the last definition, so that is the line that changes");
    }

    @Test
    void aKeyWithAnInteriorSpaceIsEscapedSoItSurvivesTheLoader() {
        // The loader (and this reader) end the key at the first blank, so an unescaped interior
        // space turned the key "a b" into the key "a" with the value "b=c".
        PropertiesFile file = PropertiesFile.parse("z = 1\n");
        file.put("a b", "c");
        assertEquals("z = 1\na\\ b=c\n", file.write());

        PropertiesFile reparsed = PropertiesFile.parse(file.write());
        assertEquals("c", reparsed.values.get("a b"));
        assertEquals("1", reparsed.values.get("z"));
        assertNull(reparsed.values.get("a"), "no phantom key may appear");
    }

    @Test
    void anAppendedKeyIsNotSwallowedByATrailingContinuation() {
        // "a = x\" ends in a continuation, so a key written on the next line became part of a's
        // value ("xb=1") and the new key vanished.
        PropertiesFile file = PropertiesFile.parse("a = x\\");
        file.put("b", "1");
        String written = file.write();
        assertEquals("a = x\\\\\nb=1\n", written,
            "the dangling continuation is made literal before the new key");

        PropertiesFile reparsed = PropertiesFile.parse(written);
        assertEquals("x\\", reparsed.values.get("a"));
        assertEquals("1", reparsed.values.get("b"), "the appended key must be its own entry");
    }

    @Test
    void anInvalidUnicodeEscapeIsReportedInsteadOfSilentlyRekeyed() {
        // The game's loader throws on a \\u without four hex digits and drops the entire bundle, so
        // the tool must not pretend the file is fine. The literal text keeps its backslash: the
        // decoded value must not silently change.
        PropertiesFile file = PropertiesFile.parse("a = b\\u12\n");
        assertEquals("b\\u12", file.values.get("a"), "the literal text is kept");
        assertFalse(file.problems.isEmpty(), "the malformed escape must be reported");
        assertTrue(file.problems.get(0).contains("u escape"), file.problems.toString());

        PropertiesFile ok = PropertiesFile.parse("a = b\\u0041c\n");
        assertEquals("bAc", ok.values.get("a"));
        assertTrue(ok.problems.isEmpty(), ok.problems.toString());
    }

    @Test
    void aLeadingBomIsReportedWithoutBreakingTheByteForByteRoundTrip() {
        // The game keeps the BOM inside the first key's name, so that key is dead in game; the tool
        // still preserves the bytes (the contract) and reports the problem instead of staying blind.
        String text = "\uFEFFblock.x.name = V\nitem.y.name = W\n";
        PropertiesFile file = PropertiesFile.parse(text);
        assertEquals("V", file.values.get("block.x.name"));
        assertFalse(file.problems.isEmpty(), "the BOM must be reported");
        assertTrue(file.problems.get(0).contains("BOM"), file.problems.toString());
        assertEquals(text, file.write(), "an untouched file keeps its bytes, BOM included");
    }

    @Test
    void reservedWindowsDeviceNamesAreRejected() {
        assertFalse(NameRules.isValidContentName("con"));
        assertFalse(NameRules.isValidContentName("CON"));
        assertFalse(NameRules.isValidContentName("com1"));
        assertFalse(NameRules.isValidContentName("nul.bak"));
        assertFalse(NameRules.isFolderSafe("nul"));
        assertFalse(NameRules.isFolderSafe("con"));
        assertTrue(NameRules.isValidContentName("console"), "a longer word is not the device name");
        assertTrue(NameRules.isFolderSafe("console"));
        assertTrue(NameRules.isWindowsReservedName("con.bak"), "reserved even with an extension");
        assertFalse(NameRules.isWindowsReservedName("conveyor"));
    }

    @Test
    void overlongNamesAreRejected() {
        assertFalse(NameRules.isValidContentName("a".repeat(200)));
        assertFalse(NameRules.isFolderSafe("a".repeat(200)));
        assertTrue(NameRules.isValidContentName("a".repeat(100)));
    }
}

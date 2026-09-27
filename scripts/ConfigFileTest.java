package dev.willtda.simpleschematics.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

/**
 * Checks the mod's own settings file against one Forge's config spec wrote, and
 * how it copes with hand edits. Needs no game classes to run.
 */
public final class ConfigFileTest {
    private static int checks;

    enum Corner { TOP_LEFT, BOTTOM_RIGHT }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath();
        fidelity(root.resolve("scripts/fixtures/forge-written-client.toml"));
        corrections();
        strings();
        reload();
        System.out.println("Config file checks passed: " + checks);
    }

    /** A file Forge wrote loads with every value intact and saves back byte for byte. */
    private static void fidelity(Path fixture) throws Exception {
        String forge = Files.readString(fixture, StandardCharsets.UTF_8);
        Path copy = Files.createTempDirectory("config-fidelity").resolve(SSConfig.FILE_NAME);
        Files.writeString(copy, forge, StandardCharsets.UTF_8);
        FileTime before = Files.getLastModifiedTime(copy);
        SSConfig.FILE.load(copy);
        check(Files.getLastModifiedTime(copy).equals(before), "A complete, valid file must not be rewritten on load");
        Map<String, String> raw = ConfigFile.parse(forge);
        check(raw.size() == SSConfig.FILE.values().size(), "Every setting Forge wrote must still be defined, and no more");
        for (ConfigFile.Value<?> value : SSConfig.FILE.values()) {
            check(raw.get(value.path()).equals(value.text()), "Loaded value must match the file for " + value.path());
        }
        check(SSConfig.FILE.write().equals(forge), "Saving must reproduce Forge's layout exactly");
    }

    private static void corrections() throws Exception {
        ConfigFile.Builder b = new ConfigFile.Builder();
        b.comment("Section comment").push("general");
        ConfigFile.BooleanValue flag = b.comment("A flag").define("flag", true);
        ConfigFile.IntValue count = b.defineInRange("count", 4, 1, 40);
        ConfigFile.DoubleValue share = b.defineInRange("share", 0.5D, 0.0D, 1.0D);
        ConfigFile.StringValue name = b.define("name", "stick");
        ConfigFile.EnumValue<Corner> corner = b.defineEnum("corner", Corner.BOTTOM_RIGHT);
        b.pop();
        ConfigFile file = b.build();

        Path folder = Files.createTempDirectory("config-corrections");
        Path path = folder.resolve("nested").resolve("settings.toml");
        file.load(path);
        check(Files.exists(path), "A missing file is written with defaults");
        check(flag.get() && count.get() == 4 && share.get() == 0.5D && name.get().equals("stick")
                && corner.get() == Corner.BOTTOM_RIGHT, "A missing file leaves every default");
        String written = Files.readString(path);
        check(written.contains("\t#Range: 1 ~ 40\n\tcount = 4\n"), "Ranges are written the way Forge wrote them");
        check(written.contains("\t#Allowed Values: TOP_LEFT, BOTTOM_RIGHT\n"), "Enum choices are listed the way Forge listed them");
        check(written.startsWith("\n#Section comment\n[general]\n\t#A flag\n\tflag = true\n"), "Sections and comments keep Forge's layout");

        Files.writeString(path, "\uFEFF[general]\r\nflag = false # switched off by hand\r\ncount = 99\r\nshare = 1\r\n"
                + "name = 7\r\ncorner = \"top_left\"\r\nunknown = true\r\n");
        file.load(path);
        check(!flag.get(), "A trailing comment after a value is ignored, BOM and CRLF included");
        check(count.get() == 4, "An out of range number falls back to the default");
        check(share.get() == 1.0D, "A whole number is a valid decimal");
        check(name.get().equals("stick"), "A mistyped string falls back to the default");
        check(corner.get() == Corner.TOP_LEFT, "Enum names are read regardless of case");
        String corrected = Files.readString(path);
        check(corrected.contains("\tcount = 4\n") && !corrected.contains("unknown"),
                "Corrections are written back and unknown keys dropped");

        count.set(12);
        file.save();
        file.load(path);
        check(count.get() == 12, "A saved change survives a reload");
    }

    private static void strings() {
        for (String text : new String[]{"", "plain", "C:\\Users\\Will\\OneDrive", "quote \" inside", "tab\tand\nnewline", "é ✔"}) {
            check(text.equals(ConfigFile.unquote(ConfigFile.quote(text))), "Strings must round trip: " + text);
        }
        check("C:\\Users\\Will".equals(ConfigFile.unquote("\"C:\\Users\\Will\"")),
                "A hand typed Windows path with single backslashes is kept as written");
        check("literal \\n".equals(ConfigFile.unquote("'literal \\n'")), "Literal strings are taken as written");
        check("\u00e9".equals(ConfigFile.unquote("\"\\u00e9\"")), "Unicode escapes are read");
        check(ConfigFile.unquote("unquoted") == null, "Bare words are not strings");
    }

    private static void reload() throws Exception {
        ConfigFile.Builder b = new ConfigFile.Builder();
        b.push("general");
        ConfigFile.IntValue count = b.defineInRange("count", 4, 1, 40);
        b.pop();
        ConfigFile file = b.build();
        Path path = Files.createTempDirectory("config-reload").resolve("settings.toml");
        file.load(path);
        check(!file.reloadIfChanged(), "An untouched file is not read again");
        count.set(8);
        file.save();
        check(!file.reloadIfChanged(), "The mod's own save is not mistaken for an edit");
        Files.writeString(path, "[general]\n\tcount = 17\n");
        Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        check(file.reloadIfChanged() && count.get() == 17, "A hand edit while the game runs is picked up");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

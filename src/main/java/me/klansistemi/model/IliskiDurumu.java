package me.klansistemi.model;

import org.bukkit.ChatColor;
import org.bukkit.Material;

public enum IliskiDurumu {
    DOST("Dost", ChatColor.GREEN, Material.LIME_BANNER),
    TARAFSIZ("Tarafsız", ChatColor.GRAY, Material.LIGHT_GRAY_BANNER),
    HUSUMET("Husumet", ChatColor.RED, Material.RED_BANNER);

    public final String ad;
    public final ChatColor renk;
    public final Material banner;

    IliskiDurumu(String ad, ChatColor renk, Material banner) {
        this.ad = ad;
        this.renk = renk;
        this.banner = banner;
    }

    public String renkliAd() {
        return renk + ad;
    }

    public static IliskiDurumu oku(String metin) {
        return switch (metin.toLowerCase(java.util.Locale.ROOT)) {
            case "dost", "ittifak" -> DOST;
            case "tarafsiz", "tarafsız", "notr" -> TARAFSIZ;
            case "husumet", "dusman", "düşman" -> HUSUMET;
            default -> null;
        };
    }
}

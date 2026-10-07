package me.klansistemi.model;

import org.bukkit.ChatColor;

public enum Rol {
    LIDER("Lider", ChatColor.GOLD),
    YARDIMCI("Yardımcı", ChatColor.YELLOW),
    UYE("Üye", ChatColor.GRAY);

    public final String ad;
    public final ChatColor renk;

    Rol(String ad, ChatColor renk) {
        this.ad = ad;
        this.renk = renk;
    }

    public String renkliAd() {
        return renk + ad;
    }
}

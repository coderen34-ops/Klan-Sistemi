package me.klansistemi;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** MeslekSistemi başarım sayacını konsol komutuyla artırır (derleme bağımlılığı yok; komut yoksa sessizce atlar). */
public final class BasarimKopru {
    private BasarimKopru() {}

    public static void sayac(Player p, String sayac) {
        if (p == null || !p.isOnline() || !p.getName().matches("[A-Za-z0-9_]{1,16}")) return;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "basarimsayac " + p.getName() + " " + sayac + " 1");
        } catch (Throwable ignored) {
            // başarım sistemi olmasa da oyun akışı bozulmasın
        }
    }
}

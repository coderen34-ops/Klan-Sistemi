package me.ailesistemi.komut;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.ailesistemi.AileSistemi;

/** /ac <mesaj>: aile sohbetine tek seferlik mesaj. */
public class AcKomut implements CommandExecutor {

    private final AileSistemi plugin;

    public AcKomut(AileSistemi plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length == 0) {
            plugin.mesaj().gonder(p, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/ac <mesaj>");
            return true;
        }
        plugin.aileManager().aileSohbeti(p, String.join(" ", args));
        return true;
    }
}

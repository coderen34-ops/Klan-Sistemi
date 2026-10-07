package me.klansistemi.komut;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.klansistemi.KlanSistemi;

/** /kc <mesaj>: klan sohbetine tek seferlik mesaj. */
public class KcKomut implements CommandExecutor {

    private final KlanSistemi plugin;

    public KcKomut(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length == 0) {
            plugin.mesaj().gonder(p, "kullanim", "&cKullanım: &e{kullanim}", "kullanim", "/kc <mesaj>");
            return true;
        }
        plugin.klanManager().klanSohbeti(p, String.join(" ", args));
        return true;
    }
}

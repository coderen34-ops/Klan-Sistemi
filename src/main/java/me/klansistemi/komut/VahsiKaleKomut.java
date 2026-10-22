package me.klansistemi.komut;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import me.klansistemi.KlanSistemi;

/** /vahsikale <uret [seviye]|sil|liste|bilgi|giris>: Vahşi Kale (NPC klanı) admin komutları. */
public class VahsiKaleKomut implements CommandExecutor, TabCompleter {

    private final KlanSistemi plugin;

    public VahsiKaleKomut(KlanSistemi plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        plugin.vahsiKale().komut(sender, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("uret", "sil", "liste", "bilgi", "giris");
        if (args.length == 2 && args[0].equalsIgnoreCase("uret")) return List.of("1", "2", "3", "4", "5");
        return List.of();
    }
}

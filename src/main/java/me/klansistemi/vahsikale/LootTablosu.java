package me.klansistemi.vahsikale;

import java.util.Random;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

/**
 * Vahşi Kale sandıklarını seviyeye göre gerçek item'larla doldurur (önceden sandıklar boştu,
 * sadece kırılınca klan kasasına nakit aktarılıyordu - bu sınıf fiziksel loot'u ekler).
 */
public final class LootTablosu {

    private LootTablosu() { }

    public static void doldur(Inventory envanter, int seviye, Random rng) {
        int yuvaSayisi = 3 + rng.nextInt(3) + seviye; // seviye arttıkça biraz daha dolu
        for (int i = 0; i < yuvaSayisi; i++) {
            int yuva = rng.nextInt(envanter.getSize());
            if (envanter.getItem(yuva) != null) continue;
            envanter.setItem(yuva, rastgeleItem(seviye, rng));
        }
    }

    private static ItemStack rastgeleItem(int seviye, Random rng) {
        double zar = rng.nextDouble();
        // Seviye yükseldikçe nadir item ihtimali artar
        double nadirEsik = 0.05 + seviye * 0.03;
        double iyiEsik = 0.35 + seviye * 0.05;

        if (zar < nadirEsik) {
            return nadirItem(seviye, rng);
        } else if (zar < iyiEsik) {
            return iyiItem(seviye, rng);
        }
        return sıradanItem(rng);
    }

    private static ItemStack sıradanItem(Random rng) {
        Material[] secenekler = {Material.BREAD, Material.ARROW, Material.IRON_INGOT, Material.COAL,
                Material.LEATHER, Material.STRING, Material.TORCH, Material.GUNPOWDER};
        Material m = secenekler[rng.nextInt(secenekler.length)];
        int miktar = switch (m) {
            case ARROW -> 8 + rng.nextInt(16);
            case TORCH -> 4 + rng.nextInt(8);
            default -> 1 + rng.nextInt(4);
        };
        return new ItemStack(m, miktar);
    }

    private static ItemStack iyiItem(int seviye, Random rng) {
        Material[] secenekler = {Material.GOLD_INGOT, Material.IRON_INGOT, Material.EMERALD,
                Material.EXPERIENCE_BOTTLE, Material.CROSSBOW, Material.SHIELD, Material.NAME_TAG, Material.SADDLE};
        Material m = secenekler[rng.nextInt(secenekler.length)];
        int miktar = (m == Material.GOLD_INGOT || m == Material.IRON_INGOT || m == Material.EMERALD || m == Material.EXPERIENCE_BOTTLE)
                ? 1 + rng.nextInt(seviye + 2) : 1;
        return new ItemStack(m, miktar);
    }

    private static ItemStack nadirItem(int seviye, Random rng) {
        if (seviye >= 4 && rng.nextDouble() < 0.15) {
            return new ItemStack(Material.TOTEM_OF_UNDYING, 1);
        }
        if (rng.nextBoolean()) {
            return new ItemStack(Material.DIAMOND, 1 + rng.nextInt(seviye));
        }
        ItemStack kitap = new ItemStack(Material.ENCHANTED_BOOK, 1);
        if (kitap.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            Enchantment[] havuz = {Enchantment.SHARPNESS, Enchantment.PROTECTION, Enchantment.UNBREAKING,
                    Enchantment.FORTUNE, Enchantment.POWER, Enchantment.MENDING};
            Enchantment buyu = havuz[rng.nextInt(havuz.length)];
            int seviyeBuyu = 1 + rng.nextInt(Math.min(3, seviye));
            meta.addStoredEnchant(buyu, seviyeBuyu, true);
            kitap.setItemMeta(meta);
        }
        return kitap;
    }
}

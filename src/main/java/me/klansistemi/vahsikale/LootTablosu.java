package me.klansistemi.vahsikale;

import java.util.Random;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Vahşi Kale sandıklarını seviyeye göre gerçek item'larla doldurur (önceden sandıklar boştu,
 * sadece kırılınca klan kasasına nakit aktarılıyordu - bu sınıf fiziksel loot'u ekler).
 *
 * Kullanıcı isteğiyle genişletildi: sadece hammadde değil, iksirler, altın elmalar, zırh parçaları
 * ve (çok nadir) vanilla üstü büyü seviyesine (ör. Sharpness 7) sahip büyülü kitaplar da çıkabiliyor.
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
        // Efsanevi büyü (ör. Sharpness 7) - vanilla üstü seviye, çok nadir (seviyeden bağımsız,
        // her sandıkta ayrıca denenir ki bir seviye 1 kalede bile -çok küçük ihtimalle- çıkabilsin).
        if (rng.nextDouble() < 0.01) {
            int bosYuva = ilkBosYuva(envanter, rng);
            if (bosYuva >= 0) envanter.setItem(bosYuva, efsaneviBuyuluKitap(rng));
        }
    }

    private static int ilkBosYuva(Inventory envanter, Random rng) {
        int baslangic = rng.nextInt(envanter.getSize());
        for (int i = 0; i < envanter.getSize(); i++) {
            int yuva = (baslangic + i) % envanter.getSize();
            if (envanter.getItem(yuva) == null) return yuva;
        }
        return -1;
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
        // Hammaddelerin yanına: iksirler, altın elma ve rastgele bir zırh parçası eklendi.
        int secim = rng.nextInt(10);
        if (secim == 0) return rastgeleIksir(seviye, rng);
        if (secim == 1) return new ItemStack(Material.GOLDEN_APPLE, 1 + rng.nextInt(2));
        if (secim == 2) return rastgeleZirhParcasi(rng.nextBoolean(), rng, seviye);
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
        double zar = rng.nextDouble();
        if (zar < 0.15) {
            return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1);
        }
        if (zar < 0.35) {
            return rastgeleZirhParcasi(true, rng, seviye); // nadir dalda zırh her zaman enchant'li
        }
        if (zar < 0.55) {
            return rastgeleIksir(seviye, rng);
        }
        if (zar < 0.75) {
            return new ItemStack(Material.DIAMOND, 1 + rng.nextInt(seviye));
        }
        return rastgeleBuyuluKitap(seviye, rng);
    }

    /** Vanilla sınırı aşan (ör. Sharpness 7) büyü - bkz. doldur()'daki %1 şans. */
    private static ItemStack efsaneviBuyuluKitap(Random rng) {
        Enchantment[] havuz = {Enchantment.SHARPNESS, Enchantment.PROTECTION, Enchantment.POWER};
        Enchantment buyu = havuz[rng.nextInt(havuz.length)];
        int seviyeBuyu = 6 + rng.nextInt(2); // 6 ya da 7 - vanilla max 5
        return buyuluKitapYap(buyu, seviyeBuyu);
    }

    private static ItemStack rastgeleBuyuluKitap(int seviye, Random rng) {
        Enchantment[] havuz = {Enchantment.SHARPNESS, Enchantment.PROTECTION, Enchantment.UNBREAKING,
                Enchantment.FORTUNE, Enchantment.POWER, Enchantment.MENDING};
        Enchantment buyu = havuz[rng.nextInt(havuz.length)];
        int seviyeBuyu = 1 + rng.nextInt(Math.min(3, seviye));
        return buyuluKitapYap(buyu, seviyeBuyu);
    }

    private static ItemStack buyuluKitapYap(Enchantment buyu, int seviyeBuyu) {
        ItemStack kitap = new ItemStack(Material.ENCHANTED_BOOK, 1);
        if (kitap.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            meta.addStoredEnchant(buyu, seviyeBuyu, true); // true: vanilla seviye sınırını yoksay
            kitap.setItemMeta(meta);
        }
        return kitap;
    }

    private static ItemStack rastgeleIksir(int seviye, Random rng) {
        ItemStack iksir = new ItemStack(Material.POTION, 1);
        ItemMeta meta = iksir.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.PotionMeta pm) {
            org.bukkit.potion.PotionEffectType[] havuz = {
                    org.bukkit.potion.PotionEffectType.STRENGTH, org.bukkit.potion.PotionEffectType.SPEED,
                    org.bukkit.potion.PotionEffectType.REGENERATION, org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE,
                    org.bukkit.potion.PotionEffectType.HEALTH_BOOST, org.bukkit.potion.PotionEffectType.ABSORPTION
            };
            org.bukkit.potion.PotionEffectType efekt = havuz[rng.nextInt(havuz.length)];
            int sureTick = (60 + rng.nextInt(120)) * 20; // 1-3 dakika
            int amplifier = seviye >= 4 && rng.nextBoolean() ? 1 : 0;
            pm.addCustomEffect(new org.bukkit.potion.PotionEffect(efekt, sureTick, amplifier), true);
            iksir.setItemMeta(pm);
        }
        return iksir;
    }

    private static ItemStack rastgeleZirhParcasi(boolean enchantli, Random rng, int seviye) {
        boolean demir = rng.nextBoolean();
        Material[] demirSet = {Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS};
        Material[] altinSet = {Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS};
        Material[] set = demir ? demirSet : altinSet;
        Material parca = set[rng.nextInt(set.length)];
        ItemStack esya = new ItemStack(parca, 1);
        if (enchantli) {
            ItemMeta meta = esya.getItemMeta();
            if (meta != null) {
                meta.addEnchant(Enchantment.PROTECTION, 1 + rng.nextInt(Math.min(4, Math.max(1, seviye))), true);
                if (rng.nextBoolean()) meta.addEnchant(Enchantment.UNBREAKING, 1 + rng.nextInt(2), true);
                esya.setItemMeta(meta);
            }
        }
        return esya;
    }
}

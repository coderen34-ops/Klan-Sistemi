package me.klansistemi.vahsikale;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Chain;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;

/**
 * Vahşi Kale'nin FİZİKSEL yapısını sıfırdan, saf Bukkit block API ile (WorldEdit'siz) inşa eder.
 * Bastion Remnant'a benzeyen, tamamen kapalı (çatılı, uçarak girilemeyen) bir dikdörtgen prizma:
 * blackstone ailesi + zincir/fener + tek demir kapı girişi. Senkron çalışır (admin komutu veya
 * otomatik üretim tetikleyicisi tarafından çağrılır) - ağır olabileceğinden sık çağrılmaz.
 */
public final class KaleInsaatYardimcisi {

    private KaleInsaatYardimcisi() { }

    /** İnşaat sonucu: NPC doğurma/sandık/respawn için gereken konumlar. */
    public static final class Sonuc {
        public Location girisIci;              // Respawn / "merkez" olarak kullanılır (kapının hemen içi)
        public Location liderKonumu;            // Taht platformu
        public final List<Location> nobetciKonumlari = new ArrayList<>();
        public final List<Location> sandikKonumlari = new ArrayList<>();
    }

    /**
     * @param merkez  Yapının ortalanacağı yaklaşık konum (Y önemsiz, zemin otomatik bulunur)
     * @param seviye  1-5, boyutu ve kule yüksekliğini belirler
     */
    public static Sonuc insaEt(Location merkez, int seviye, Random rng) {
        World dunya = merkez.getWorld();
        int genislik = 20 + (seviye - 1) * 4;   // X ekseni: 20..36
        int derinlik = 16 + (seviye - 1) * 4;   // Z ekseni: 16..32
        int yukseklik = 14 + (seviye - 1) * 2;  // İç yükseklik: 14..22
        int merkezX = merkez.getBlockX();
        int merkezZ = merkez.getBlockZ();
        int xMin = merkezX - genislik / 2, xMax = xMin + genislik - 1;
        int zMin = merkezZ - derinlik / 2, zMax = zMin + derinlik - 1;
        int kenarPay = 3;

        // 1) Zemin seviyesini bul: footprint + pay alanındaki en yüksek doğal bloğa göre düz bir taban belirle
        int tabanY = Integer.MIN_VALUE;
        for (int x = xMin - kenarPay; x <= xMax + kenarPay; x++) {
            for (int z = zMin - kenarPay; z <= zMax + kenarPay; z++) {
                dunya.getChunkAt(x >> 4, z >> 4).load();
                int y = dunya.getHighestBlockYAt(x, z);
                if (y > tabanY) tabanY = y;
            }
        }
        int y0 = tabanY + 1; // yapının oturduğu kat

        // 2) Üstünü (ağaç/tepe) temizle, altını (çukur varsa) doldur -> düz bir platform garantile
        for (int x = xMin - kenarPay; x <= xMax + kenarPay; x++) {
            for (int z = zMin - kenarPay; z <= zMax + kenarPay; z++) {
                for (int y = y0; y <= y0 + yukseklik + 10; y++) setHava(dunya, x, y, z);
                for (int y = y0 - 1; y >= y0 - 6; y--) {
                    Block b = dunya.getBlockAt(x, y, z);
                    if (b.getType() == Material.AIR || b.getType() == Material.WATER || b.getType() == Material.LAVA
                            || b.getType() == Material.CAVE_AIR) {
                        b.setType(Material.BLACKSTONE, false);
                    }
                }
            }
        }

        Material[] duvarKaris = {Material.BLACKSTONE, Material.BLACKSTONE, Material.POLISHED_BLACKSTONE,
                Material.POLISHED_BLACKSTONE_BRICKS, Material.CRACKED_POLISHED_BLACKSTONE_BRICKS};

        // 3) Dış duvarlar (2 blok kalın, tam çevre) + tam kapalı çatı + taban
        for (int x = xMin; x <= xMax; x++) {
            for (int z = zMin; z <= zMax; z++) {
                boolean kenarX = (x == xMin || x == xMin + 1 || x == xMax || x == xMax - 1);
                boolean kenarZ = (z == zMin || z == zMin + 1 || z == zMax || z == zMax - 1);
                if (kenarX || kenarZ) {
                    for (int y = y0; y <= y0 + yukseklik; y++) {
                        dunya.getBlockAt(x, y, z).setType(rastgeleSec(duvarKaris, rng), false);
                    }
                }
                // Taban
                dunya.getBlockAt(x, y0 - 1, z).setType(Material.POLISHED_BLACKSTONE, false);
                // Tam kapalı çatı (tek kat yeter, uçarak girişi engeller)
                dunya.getBlockAt(x, y0 + yukseklik + 1, z).setType(Material.BLACKSTONE, false);
            }
        }

        // 4) 4 köşede yükselen küçük kuleler (bastion silueti)
        int kuleBoy = 4;
        int[][] koseler = {{xMin, zMin}, {xMin, zMax - kuleBoy + 1}, {xMax - kuleBoy + 1, zMin}, {xMax - kuleBoy + 1, zMax - kuleBoy + 1}};
        for (int[] kose : koseler) {
            for (int x = kose[0]; x < kose[0] + kuleBoy; x++) {
                for (int z = kose[1]; z < kose[1] + kuleBoy; z++) {
                    boolean cerceve = (x == kose[0] || x == kose[0] + kuleBoy - 1 || z == kose[1] || z == kose[1] + kuleBoy - 1);
                    if (cerceve) {
                        for (int y = y0 + yukseklik - 2; y <= y0 + yukseklik + 5; y++) {
                            dunya.getBlockAt(x, y, z).setType(Material.GILDED_BLACKSTONE, false);
                        }
                    }
                }
            }
        }

        // 5) Tek giriş: güney duvarın (zMax) ortasında demir kapı + dışarıda buton
        int kapiX1 = merkezX, kapiX2 = merkezX + 1;
        for (int dx = 0; dx <= 1; dx++) {
            int x = (dx == 0 ? kapiX1 : kapiX2);
            for (int derinlikIdx = 0; derinlikIdx < 2; derinlikIdx++) {
                int z = zMax - derinlikIdx;
                demirKapiKoy(dunya, x, y0, z);
            }
        }
        // Dışarıdan açmak için buton (kapının hemen dışında, duvar üstünde)
        Block butonBlogu = dunya.getBlockAt(kapiX1, y0 + 1, zMax + 1);
        butonBlogu.setType(Material.STONE_BUTTON, false);
        if (butonBlogu.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
            dir.setFacing(BlockFace.SOUTH);
            butonBlogu.setBlockData(dir);
        }

        // 6) İç dekor: tavandan sarkan zincir+fener (düzenli aralıklarla), avuç avuç değil seyrek
        for (int x = xMin + 4; x <= xMax - 4; x += 5) {
            for (int z = zMin + 4; z <= zMax - 4; z += 5) {
                zincirFenerAs(dunya, x, y0 + yukseklik, z);
            }
        }

        // 7) Taht platformu (merkez, biraz yükseltilmiş) - lider burada duracak
        int tahtY = y0;
        for (int x = merkezX - 1; x <= merkezX + 1; x++) {
            for (int z = merkezZ - 1; z <= merkezZ + 1; z++) {
                dunya.getBlockAt(x, tahtY, z).setType(Material.GILDED_BLACKSTONE, false);
            }
        }
        Block endRod = dunya.getBlockAt(merkezX, tahtY + 1, merkezZ);
        endRod.setType(Material.END_ROD, false);

        Sonuc sonuc = new Sonuc();
        sonuc.liderKonumu = new Location(dunya, merkezX + 0.5, tahtY + 1, merkezZ + 0.5);
        sonuc.girisIci = new Location(dunya, merkezX + 0.5, y0 + 1, zMax - 2.5);

        // 8) Nöbetçi doğum noktaları: iç mekanda dağınık
        int nobetciHedef = Math.max(4, seviye * 3);
        int denemeLimiti = nobetciHedef * 6;
        int basarili = 0;
        for (int i = 0; i < denemeLimiti && basarili < nobetciHedef; i++) {
            int x = xMin + 3 + rng.nextInt(Math.max(1, genislik - 6));
            int z = zMin + 3 + rng.nextInt(Math.max(1, derinlik - 6));
            if (Math.abs(x - merkezX) <= 1 && Math.abs(z - merkezZ) <= 1) continue; // taht alanı
            sonuc.nobetciKonumlari.add(new Location(dunya, x + 0.5, y0 + 1, z + 0.5));
            basarili++;
        }

        // 9) Sandıklar: duvar diplerine 2-4 adet, köşelerden kaçınarak
        int sandikSayisi = Math.min(4, 2 + seviye / 2);
        int[][] sandikAday = {
                {xMin + 3, zMin + 3}, {xMax - 3, zMin + 3}, {xMin + 3, zMax - 3}, {xMax - 3, zMax - 3}
        };
        for (int i = 0; i < sandikSayisi; i++) {
            int x = sandikAday[i % sandikAday.length][0];
            int z = sandikAday[i % sandikAday.length][1];
            Block sandikBlok = dunya.getBlockAt(x, y0, z);
            sandikBlok.setType(Material.CHEST, false);
            sonuc.sandikKonumlari.add(sandikBlok.getLocation());
        }

        return sonuc;
    }

    private static void setHava(World dunya, int x, int y, int z) {
        Block b = dunya.getBlockAt(x, y, z);
        if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
    }

    private static Material rastgeleSec(Material[] secenekler, Random rng) {
        return secenekler[rng.nextInt(secenekler.length)];
    }

    private static void demirKapiKoy(World dunya, int x, int y, int z) {
        Block alt = dunya.getBlockAt(x, y, z);
        Block ust = dunya.getBlockAt(x, y + 1, z);
        alt.setType(Material.IRON_DOOR, false);
        ust.setType(Material.IRON_DOOR, false);
        if (alt.getBlockData() instanceof Door altKapi) {
            altKapi.setHalf(Door.Half.BOTTOM);
            altKapi.setFacing(BlockFace.NORTH);
            alt.setBlockData(altKapi);
        }
        if (ust.getBlockData() instanceof Door ustKapi) {
            ustKapi.setHalf(Door.Half.TOP);
            ustKapi.setFacing(BlockFace.NORTH);
            ust.setBlockData(ustKapi);
        }
    }

    private static void zincirFenerAs(World dunya, int x, int tavanY, int z) {
        Block zincir = dunya.getBlockAt(x, tavanY - 1, z);
        zincir.setType(Material.CHAIN, false);
        if (zincir.getBlockData() instanceof Chain c) {
            c.setAxis(org.bukkit.Axis.Y);
            zincir.setBlockData(c);
        }
        Block fenerBlok = dunya.getBlockAt(x, tavanY - 2, z);
        fenerBlok.setType(Material.SOUL_LANTERN, false);
        BlockData data = fenerBlok.getBlockData();
        if (data instanceof Lantern l) {
            l.setHanging(true);
            fenerBlok.setBlockData(l);
        }
        if (data instanceof Lightable lit) {
            lit.setLit(true);
            fenerBlok.setBlockData(data);
        }
    }
}

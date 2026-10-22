package me.klansistemi.vahsikale;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;

/**
 * Bir Vahşi Kale (NPC klanı) kaydı. Bellekte tutulur, SQLite'a VahsiKaleYonetici üzerinden
 * yazılır/okunur. Fiziksel yapı (dünya üretimi) bu sınıfın parçası değil - burada sadece
 * konum/yarıçap/nöbetçi/sandık KAYITLARI tutulur; yapının kendisi ayrıca (manuel/script ile) inşa edilmiş olmalı.
 */
public class VahsiKale {

    public final UUID id;
    public String isim;
    public int seviye; // 1-5
    public Location merkez;
    public Location giris; // Fethedilmemişken kuşatmada ölenler buraya respawn olur; ayarlanmadıysa merkez kullanılır
    public double tespitYaricap;
    public double saldiriYaricap;

    // "VINDICATOR" (ham, disguise yok) ya da "INSAN" (PlayerDisguise ile giydirilmiş) - kaleden kaleye
    // farklı bir klan hissi versin diye üretimde rastgele seçilir.
    public String klanTuru = "INSAN";

    // Yapının tam sınır kutusu (blok koordinatları, dahil) - fethedilene kadar sadece demir kapının
    // kırılabilmesi, diğer tüm blokların korunması için (bkz. VahsiKaleYonetici#onYapiKorumasi).
    public int sinirMinX, sinirMaxX, sinirMinY, sinirMaxY, sinirMinZ, sinirMaxZ;

    public boolean sinirIcindeMi(Location l) {
        if (merkez == null || merkez.getWorld() == null || l.getWorld() == null || !l.getWorld().equals(merkez.getWorld())) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= sinirMinX && x <= sinirMaxX && y >= sinirMinY && y <= sinirMaxY && z >= sinirMinZ && z <= sinirMaxZ;
    }

    public boolean fethedildi;
    public UUID fetihedenKlanId;
    public long fetihZamani;
    public boolean prestijVerildi;

    // npcUuid -> rol ("NOBETCI" ya da "LIDER")
    public final Map<UUID, String> nobetciRolleri = new LinkedHashMap<>();
    // npcUuid -> PlayerDisguise skin adı (null = disguise yok, ham Vindicator görünümü)
    public final Map<UUID, String> nobetciSkinleri = new LinkedHashMap<>();
    // Ölmüş (idempotent kilit açma kontrolü için - entity dünyadan kaybolsa/chunk unload olsa bile kalıcı)
    public final Set<UUID> oluNobetciler = new LinkedHashSet<>();

    // Sandık konumları, "dünya;x;y;z" formatında (konumYaz ile üretilir) -> açıldı mı
    public final Map<String, Boolean> sandiklar = new LinkedHashMap<>();

    // Runtime-only: şu an bu kalede kuşatmada sayılan oyuncular (persist edilmez)
    public final Set<UUID> kusatmadakiOyuncular = new LinkedHashSet<>();

    public VahsiKale(UUID id) {
        this.id = id;
    }

    public boolean tumuOldu() {
        return oluNobetciler.containsAll(nobetciRolleri.keySet()) && !nobetciRolleri.isEmpty();
    }
}

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

    public boolean fethedildi;
    public UUID fetihedenKlanId;
    public long fetihZamani;
    public boolean prestijVerildi;

    // npcUuid -> rol ("NOBETCI" ya da "LIDER")
    public final Map<UUID, String> nobetciRolleri = new LinkedHashMap<>();
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

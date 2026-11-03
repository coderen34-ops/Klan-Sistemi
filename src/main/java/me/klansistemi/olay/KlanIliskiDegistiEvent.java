package me.klansistemi.olay;

import java.util.UUID;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * İki klan arasındaki RESMİ ilişki (DOST/TARAFSIZ/HUSUMET) değiştiğinde. baslatan = görüşü yazan lider. DOST = karşılıklı DOST görüşüyle kurulan ittifak.
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class KlanIliskiDegistiEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String klanA;
    private final String klanB;
    private final String eskiDurum;
    private final String yeniDurum;
    private final UUID baslatan;

    public KlanIliskiDegistiEvent(String klanA, String klanB, String eskiDurum, String yeniDurum, UUID baslatan) {
        this.klanA = klanA;
        this.klanB = klanB;
        this.eskiDurum = eskiDurum;
        this.yeniDurum = yeniDurum;
        this.baslatan = baslatan;
    }

    public String getKlanA() { return klanA; }
    public String getKlanB() { return klanB; }
    public String getEskiDurum() { return eskiDurum; }
    public String getYeniDurum() { return yeniDurum; }
    public UUID getBaslatan() { return baslatan; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

package me.klansistemi.olay;

import java.util.UUID;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Bir klan lideri başka bir klan hakkında görüş (durum + not) yazdığında. klanAdi = yazan klan, hedefKlan = hakkında yazılan klan, metin = not (boş olabilir).
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class KlanGorusEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String klanAdi;
    private final UUID yazan;
    private final String metin;
    private final String hedefKlan;

    public KlanGorusEvent(String klanAdi, UUID yazan, String metin, String hedefKlan) {
        this.klanAdi = klanAdi;
        this.yazan = yazan;
        this.metin = metin;
        this.hedefKlan = hedefKlan;
    }

    public String getKlanAdi() { return klanAdi; }
    public UUID getYazan() { return yazan; }
    public String getMetin() { return metin; }
    public String getHedefKlan() { return hedefKlan; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

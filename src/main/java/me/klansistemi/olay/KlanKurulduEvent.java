package me.klansistemi.olay;

import java.util.UUID;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Klan kurulduğunda (kuran = lider).
 * Senkron (ana thread) yayınlanır; iptal edilemez. Yalnızca bildirim amaçlıdır, oyun mantığını değiştirmez.
 */
public class KlanKurulduEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String klanAdi;
    private final UUID kuran;

    public KlanKurulduEvent(String klanAdi, UUID kuran) {
        this.klanAdi = klanAdi;
        this.kuran = kuran;
    }

    public String getKlanAdi() { return klanAdi; }
    public UUID getKuran() { return kuran; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

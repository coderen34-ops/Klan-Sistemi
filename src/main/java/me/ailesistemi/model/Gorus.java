package me.ailesistemi.model;

import java.util.UUID;

/** Bir ailenin başka bir aile hakkındaki resmi görüşü (durum + kısa not). */
public class Gorus {
    public final UUID yazanAile;
    public final UUID hedefAile;
    public IliskiDurumu durum;
    public String not;          // En fazla 100 karakter, filtreli. Boş olabilir.
    public String yazan;        // Görüşü yazan patronun adı
    public long zaman;

    public Gorus(UUID yazanAile, UUID hedefAile, IliskiDurumu durum, String not, String yazan, long zaman) {
        this.yazanAile = yazanAile;
        this.hedefAile = hedefAile;
        this.durum = durum;
        this.not = not == null ? "" : not;
        this.yazan = yazan;
        this.zaman = zaman;
    }
}

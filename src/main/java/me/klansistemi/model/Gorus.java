package me.klansistemi.model;

import java.util.UUID;

/** Bir klanın başka bir klan hakkındaki resmi görüşü (durum + kısa not). */
public class Gorus {
    public final UUID yazanKlan;
    public final UUID hedefKlan;
    public IliskiDurumu durum;
    public String not;          // En fazla 100 karakter, filtreli. Boş olabilir.
    public String yazan;        // Görüşü yazan liderin adı
    public long zaman;

    public Gorus(UUID yazanKlan, UUID hedefKlan, IliskiDurumu durum, String not, String yazan, long zaman) {
        this.yazanKlan = yazanKlan;
        this.hedefKlan = hedefKlan;
        this.durum = durum;
        this.not = not == null ? "" : not;
        this.yazan = yazan;
        this.zaman = zaman;
    }
}

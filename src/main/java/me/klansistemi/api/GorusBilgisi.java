package me.klansistemi.api;

import java.util.UUID;

/** durum: DOST, TARAFSIZ veya HUSUMET (yazan klanın görüşü). */
public record GorusBilgisi(UUID yazanKlan, UUID hedefKlan, String durum, String not, String yazanLider, long zaman) { }

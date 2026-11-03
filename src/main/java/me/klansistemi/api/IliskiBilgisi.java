package me.klansistemi.api;

import java.util.UUID;

/** Resmi ilişki: iki tarafın görüşünden doğan sonuç (karşılıklı DOST = ittifak, herhangi biri HUSUMET = husumet). */
public record IliskiBilgisi(UUID klanA, UUID klanB, String durum) { }

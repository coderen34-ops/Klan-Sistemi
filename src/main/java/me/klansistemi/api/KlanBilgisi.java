package me.klansistemi.api;

import java.util.List;
import java.util.UUID;

public record KlanBilgisi(UUID id, String ad, long kurulus, double kasa, int prestij, List<UyeBilgisi> uyeler) { }

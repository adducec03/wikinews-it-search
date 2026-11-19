#!/bin/bash

cd wikinews

./mvnw test                                                  # 13 test funzionali su indice in memoria (+ test di metriche)
./mvnw test -Dtest=EfficiencyEvaluationTest -DexcludedGroups=none     # codec: tempo, dimensione, latenza (circa 1-2 minuti)
./mvnw test -Dtest=RealIndexPerformanceTest -DexcludedGroups=none     # latenza per tipo di query
./mvnw test -Dtest=KnownItemEvaluationTest  -DexcludedGroups=none     # known-item
./mvnw test -Dtest=PoolBuilderTest          -DexcludedGroups=none     # genera il pool da giudicare
./mvnw test -Dtest=RetrievalEvaluationTest  -DexcludedGroups=none     # P@10, Recall@100, MAP, MRR, nDCG@10

# Used only by -PsunovelsPrivateAcceptance=true; production keep policy is unchanged.
# Injekt reads generic superclass signatures; retain token classes without freezing their names.
-keep,allowobfuscation class * extends uy.kohesive.injekt.api.FullTypeReference { <init>(...); }

# Binary boundary consumed by externally loaded manga/manhwa extensions.
# Consumer rules already retain source.model and source.online implementations.
-keep,allowoptimization interface eu.kanade.tachiyomi.source.Source { public protected *; }
-keep,allowoptimization interface eu.kanade.tachiyomi.source.CatalogueSource { public protected *; }
-keep,allowoptimization interface eu.kanade.tachiyomi.source.SourceFactory { public protected *; }
-keep,allowoptimization interface eu.kanade.tachiyomi.source.ConfigurableSource { public protected *; }
-keep,allowoptimization interface eu.kanade.tachiyomi.source.UnmeteredSource { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.source.Source$DefaultImpls { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.source.CatalogueSource$DefaultImpls { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.source.ConfigurableSource$DefaultImpls { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.source.ConfigurableSourceKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.util.RxExtensionKt { public protected *; }

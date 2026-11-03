# Spotify Android 9.2.0.26 — moteur de lecture et livraison audio

Analyse statique du build 148373686. Les offsets hexadécimaux de cette page désignent `lib/arm64-v8a/liborbit-jni-spotify.so` dans le split ARM64, sauf mention d’un DEX. Les adresses ELF et offsets fichier sont égaux dans les sections exécutables et en lecture seule citées. SHA-256 de la bibliothèque `2353f26f5e174917b36c3c9de4538717c6348cc0bacf8b2c094f22d1447538e2`, Build ID `a80ecaa87cb98759`.

## 1. Du contrôle Android au moteur

Le manifeste du split principal déclare `SpotifyMediaBrowserService` avec l’action `android.media.browse.MediaBrowserService`. Dans son bytecode, `e(MediaSessionCompat.Token)` transmet le jeton à `p.ur00.z`. `ForegroundKeeperService` déclare les types `dataSync|mediaPlayback`. Ce sont les surfaces d’intégration Android et de maintien en arrière-plan.

`com.spotify.player.model.PlayerState` (`classes2.dex`) sépare l’URI de contexte du titre courant et expose les restrictions de contexte et de lecture, la position horodatée, le format, la qualité, l’état de mise en mémoire tampon, l’état pause/lecture, la révision de file, les titres précédents et suivants ainsi que les identifiants de session et de lecture. Un titre jouable et un contexte de lecture sont deux objets distincts. Une playlist ou un album fournit un contexte dans lequel le titre et son voisinage peuvent changer.

Les descripteurs natifs `es_play.proto`, `es_pause.proto`, `es_context_player_state.proto` et `es_context_player_options.proto` matérialisent le contrat de commandes et d’état Esperanto. `PlayRequest` contient notamment une demande de préparation, des options de lecture, des options de commande et une politique de flux. `PlayPreparedRequest` possède un identifiant de session. `Transport` dans `classes.dex` expose `callSingle`, `callStream` et `callSync` sur des noms de service/méthode et une charge binaire. Le routeur Cosmos du même APK expose `NativeRouter.resolve(Request, ResolveCallback)` en JNI. Ces interfaces attestent de frontières internes distinctes. L’entrée UI exacte jusqu’à l’instanciation de chaque composant audio n’a pas été suivie pour chaque contexte.

Côté natif, les références à des journaux de `AudioTrackPlayer` se trouvent dans des procédures distinctes pour le démarrage de la piste courante (`0xf2aca4`), la transition vers une piste préparée (`0xf2df40`) et le redémarrage après erreur (`0xf28710`). Elles documentent des transitions compilées. Les circonstances précises qui les déclenchent restent dépendantes de l’état réel du lecteur.

## File d’attente et téléchargements

**File.** Dans `classes2.dex`, `p.tuz.b(SetQueueCommand)` transforme la révision de file en entier avec `Long.parseLong`, convertit les listes précédentes et suivantes, puis appelle `ClientBase.callSingle` sur `spotify.player.esperanto.proto.ContextPlayer`, méthode `SetQueue`. Le descripteur natif `es_set_queue_request.proto` (`0x63012c..0x630365`) confirme `next_tracks = 1`, `prev_tracks = 2` et `queue_revision = 3`. Le même adaptateur appelle aussi `GetQueue` et `AddToQueue`. Le moteur possède des chemins qui abandonnent un retrait sans UID correspondant (`0x11571c8`) ou une édition de tranche sans ancres valides (`0x11599c4`). La file interne utilise donc des identifiants d’entrées et une révision. La stratégie exacte de rapprochement des révisions et la correspondance réseau pour un autre appareil restent ouvertes. Preuve REA [appel SetQueue](preuves/rea-queue-call.json), `ev_19d45278d94c688d94df972ce1a907dfe9429ab14425add00a0f364046e842a3`.

**Téléchargements.** `p.vpp0.a` (`classes2.dex`) appelle `spotify.offline_esperanto.proto.Offline/AddDownload` avec un `DownloadRequest`. `p.oqp0.c` (`classes9.dex`) appelle `RemoveDownload`. `p.byp0.a` (`classes2.dex`) construit la requête et appelle l’adaptateur d’ajout à `+0x24` unités de code DEX. Le descripteur `es_offline.proto` (`0x603358..0x606310`) confirme les champs `device = 1` et `uri = 2`, des flux d’événements pour la progression et les sessions de téléchargement, ainsi que `LicenseStatus` et `GetOfflineLicenseInfo`. La requête de téléchargement, son avancement et l’autorisation hors-ligne sont des états distincts. Le trajet complet de renouvellement de la licence et la compatibilité avec un compte et appareil tiers restent ouverts. Preuve REA [appel AddDownload](preuves/rea-offline-call.json), `ev_3ffb87e132709468b523bd3fc9de9d7eea2f9047d691b49b45b709810690f596`.

Le natif montre aussi une construction avec `GET` et le suffixe `/licenseStatus` dans `0xd68a08` (`0xd68a64` et `0xd68a74`). Le bloc `0xd7a93c` distingue des chemins de stockage de clés et de cache de clés (`0xd7b5e4`, `0xd7b688`, `0xd7b6a4`, `0xd7b6c0`). Ces références documentent des branches d’autorisation hors-ligne et des emplacements logiques. Le suffixe de statut ne permet pas d’identifier le protocole d’obtention de la clé de lecture.

Les deux relevés REA portent sur une projection de `classes2.dex` original, SHA-256 `6f75b6523f9c11d4de3cefa3209d9d408c61e07caaa12d42155b3c9141b0b532`, et ont été recoupés au smali de l’APK complet. La [matrice SDK](sdk-spotify.md) précise les commandes de file et de lecture hors-ligne disponibles pour Spotifeur.

## 2. Identifiants, variantes et qualité

| Identifiant | Rôle observé |
| --- | --- |
| URI de contexte | Identifie la playlist, l’album ou une autre source de lecture dans le modèle du lecteur |
| URI ou GID de titre | Identifie l’œuvre de catalogue, indépendamment du fichier effectivement sélectionné |
| `file_id` | Identifie une variante média, sous forme d’octets dans `AudioFile` |
| `audio_id` | Identifiant binaire distinct, présent dans l’extension audio |
| `playback_id` | Identifiant d’une instance de lecture, observable dans le modèle et dans un en-tête conditionnel du chemin CDN |

Le descripteur `metadata.proto` (`0x6713c0..0x6737f4`, fin exclue) déclare `Track.gid = 1`, `restriction = 11`, `file = 12` répété, `alternative = 13` répété, `preview = 15` répété, `availability = 19`, `original_audio = 24` et `audio_formats = 41`. Chaque `AudioFile` contient `file_id = 1` (`bytes`) et `format = 2` (enum). Dans cet enum précis, Vorbis 96, 160 et 320 sont 0, 1 et 2, AAC 24 et 48 sont 8 et 9, FLAC est 16 et FLAC 24 bits est 22. Les enums des autres descripteurs appartiennent à leur propre espace de valeurs.

`audio_files_extension.proto` (`0x67064c..0x670934`) dépend de `metadata.proto`. Sa réponse regroupe des fichiers étendus, une normalisation par défaut pour le fichier et une pour l’album, et un `audio_id`. Chaque fichier étendu contient `file = 1` et `average_bitrate = 4`. La normalisation expose `loudness_db` et `true_peak_db` en flottants. La bibliothèque prépare aussi les routes Extended Metadata v0, v3 batch et v3 config dans une procédure native autour de `0xcc574c` (références à `0xcc6248`, `0xcc6318` et `0xcc6614`). Les métadonnées de catalogue et le média choisi sont des étapes séparées. L’extension expose des paramètres de normalisation, tandis que la formule et l’ordre exact d’application dans le moteur restent à établir.

Le descripteur Net Fortune (`0x5b889c..0x5b8a7c`) contient des estimations de débit et des conseils distincts pour le préchargement sur connexions avec et sans facturation au volume. `0x11cb618` prépare les journaux d’un `BitrateDownshiftAdviser` avec seuil critique de tampon, fenêtre de moyenne, seuil de déplétion et indicateurs d’attente de clé ou de fin de tampon initial (`0x11cbf9c`). La même procédure journalise limite de débit, délai et taille de découpage lorsque le throttling est activé. Cela établit l’existence de contrôles paramétrables. La valeur des seuils et le changement de variante pendant un titre donné restent inconnus. Les niveaux présentés à l’utilisateur dépendent aussi de l’abonnement, du réseau et des réglages. [Qualité audio de Spotify](https://support.spotify.com/fr/article/audio-quality/), [normalisation Spotify](https://support.spotify.com/us/artists/article/loudness-normalization/).

## 3. Résolution du média puis transfert CDN

La procédure `0xdb8a40` assemble le chemin relatif ci-dessous. Le branchement sur l’entier à `[x19+0x38]` choisit `interactive` par défaut, `offline` pour 1 et `interactive_prefetch` pour 2.

```text
storage-resolve/v2/files/audio/{mode}/{entier}/{file_id_hex}?product=0
```

Le préfixe est inséré devant le mode à `0xdb8ae0` via `std::string::insert(0, ...)`. La valeur à `[x19+0x20]` est convertie avec `std::to_string(int)` (appel à `0xdb8afc`, relocation PLT `0x174c850`). Le bloc ajoute ensuite `/` et encode les 20 octets à `[x19+0x24]` en 40 caractères hexadécimaux minuscules. Le chemin d’encodage part de `0xdb8b28` et atteint la table `0123456789abcdef` à `0x15bdb98`. L’appel à `0xdb8b50` ajoute `?product=0`. Une branche ajoute `&partner=` et sa valeur. Le bloc `0xdb8bc0..0xdb8bd0` prépare `cache-control` et `no-cache`. Le sens fonctionnel de l’entier, la méthode HTTP effective, l’hôte et les justificatifs acceptés par le serveur restent à établir.

Le descripteur compilé `storage-resolve.proto` (`0x5b8ab4..0x5b8bb9`) définit la réponse suivante.

| Champ Protobuf | Type | Valeur |
| --- | --- | --- |
| 1 `result` | enum | `CDN = 0`, `STORAGE = 1`, `RESTRICTED = 3` |
| 2 `cdnurl` | string répété | Liste d’URL CDN |
| 4 `fileid` | bytes | Identifiant du fichier |

La branche CDN sépare donc la résolution du transfert des octets. L’énumération `STORAGE` ne suffit pas à identifier le mécanisme de repli actif. Un refus de lecture est représenté par le résultat `RESTRICTED`, distinct des erreurs réseau du CDN.

| Étape | Indice vérifié dans le code ARM64 |
| --- | --- |
| Demande du début du fichier | `0xdb33f8` possède une branche « Reading head file from cache » à `0xdb35d8` et une branche « Requesting head file from CDN » à `0xdb3898`. Cette procédure charge aussi `Accept-Encoding` et `x-playback-id` et référence une mesure `head_file_latency`. « Head file » désigne ici un objet média. Le journal seul n’identifie pas un verbe HTTP `HEAD`. |
| Demande de plage ou de fichier entier | `0xdb0550` journalise le chemin fichier entier à `0xdb0b08` et construit la variante avec `Range` à `0xdb0c2c`. Le journal du chemin de plage inclut des bornes numériques à `0xdb0cc4`. |
| En-têtes conditionnels | La même procédure référence `x-playback-id` à `0xdb0a78` et `x-segment-timestamp-ms` à `0xdb0ab8`. Leur présence dans ce client polyvalent ne démontre pas leur utilisation pour chaque piste de musique. |
| Validation de réponse | `0x14d5d74` consulte `Content-Length` et distingue les codes 200, 206 et 416. Les branches 206 et 416 se rejoignent à la lecture de `Content-Range` (`0x14d5e4c`). |
| Échec ou réponse incomplète | `0xdaf120` journalise les bornes, l’erreur et le statut HTTP. `0xdb0478` et `0xdb0518` ont des chemins distincts pour erreurs permanentes et transitoires. À `0xdab5c4`, un résultat incomplet est associé à l’invalidation du fichier en cache. |
| Préchargement | Les procédures autour de `0xca517c` journalisent le début du préchargement de la piste et de son head file. `0xca64e8` signale l’abandon du préchargement après un échec de clé récent. |

Sur le Pixel, avec Spotify 9.1.94 réglé à 160 kbit/s en Wi-Fi, le lancement d’un titre neuf produit une rafale d’environ 13 Mo en une à quatre secondes, puis plus aucun transfert notable. La rafale couvre environ 11 min d’audio et non le fichier entier : un mouvement de 26 min n’a reçu qu’environ 43 % de sa taille estimée. Sans réseau, la lecture s’arrête en `BUFFERING` à la position que couvre la rafale. Ces mesures sont compatibles avec les chemins par plage décrits ci-dessus et avec un plafond de tampon. Elles ne donnent ni la taille de chaque requête `Range`, ni le seuil de rechargement. [Mesures](validation-pixel.md#tampon-transition-et-perte-de-réseau).

Le repli de lecture disque vers le streaming possède sa propre branche à `0xdb1940`. Une procédure de `BlockScanner` (`0xc00608`) distingue l’attachement au flux de téléchargement en cours, la livraison depuis la mémoire ou le stockage, le repli vers le stockage quand l’abonnement échoue et l’abandon après épuisement d’essais. La lecture d’index (`0xcac044`) distingue notamment erreur d’accès, version, tag, checksum et identifiant. L’erreur de taille CDN à `0xdafbcc` et le changement d’URL en reprise à `0xdacde0` sont également référencés. Le lien de tous les appels de `BlockScanner` avec le parcours d’un titre précis, la politique de sélection du CDN, l’expiration, les seuils de reprise, la taille des plages et le nombre d’essais restent à établir.

## 4. Autorisation média et décodage

Le chemin natif comprend un module identifié par `cpp-audio-drm_setup_impl` (référence `0xc1171c`) et un espace de noms `spotify::drm` avec son journal `shared/audio/drm_impl/src/runtime_diag.cpp`. Le préchargement d’une clé est associé à une piste et à un fichier (`0xca4dc8`). `0x11d11a4` charge un diagnostic de vérification de clé qui distingue `wait_for_key` de `key_received`. Avec les erreurs de clé et les restrictions de lecture, ces preuves situent la clé dans le plan de lecture, distinct du transport d’octets.

### Deux générations de protection

Le code public de librespot établit la génération historique du protocole : un fichier résolu puis transféré par plages, et une **clé audio de 16 octets** demandée à la session, avec un déchiffrement **AES‑128‑CTR** à vecteur d’initialisation fixe. Ce chemin n’exige pas de DRM de plateforme. [Clé audio dans librespot](https://github.com/librespot-org/librespot/blob/dev/core/src/audio_key.rs).

Ce build Android porte les traces de cette génération, mais aussi d’une génération **Widevine**. Les chaînes `widevine` (plusieurs occurrences), `spotify.contentagnostic.v2.Widevine`, `licenseUrl`, `player_license.proto` et le message `spotify.player.proto.PlayerLicense`, `license_server_endpoint`, `switchToLicense`, `GetKeysRequest`, `_key_requester:`, `DRM key request failed` et `media_prefetcher_key_request_timeout_seconds` décrivent une demande de licence à un serveur de licences. Les présentations `MP4_128_CBCS`, `MP4_256_CBCS`, `AAC-LC 128/256 kbps cbcs`, `cenc/ctr`, l’atome `moov.trak.mdia.minf.stbl.stsd.drms` et les diagnostics `Waiting for decryptor`, `sample decryption failed`, `playback_error_drm_denied`, `playback_error_drm_unspecified` confirment un conteneur fMP4 chiffré en **CBCS ou CENC‑CTR**. Le transport interne est **Hermes** (`hm://`, `HermesSetupImpl`, `Using Hermes log transport`), au‑dessus duquel passent les requêtes de licence et d’attributs audio (`hm://audio-attributes/v1/audio-analysis/`).

### Où se trouve la clé

La distinction décisive pour un client tiers tient à l’emplacement de la clé de déchiffrement.

- Sur la génération **AES‑128‑CTR** historique, la clé de 16 octets transite par la session et le moteur la détient. C’est le chemin que librespot sait rejouer, avec les droits d’un compte Premium et les limites propres à ce projet.
- Sur la génération **Widevine**, la clé n’apparaît jamais en clair dans le binaire Orbit. Le client obtient une **licence Widevine** auprès du `license_server_endpoint`, et le déchiffrement des échantillons a lieu dans le module **Widevine de la plateforme Android** (le CDM et son `OEMCrypto`), hors du code de Spotify. `switchToLicense` et `player_license` indiquent une bascule de régime de licence par titre. Le moteur Spotify alimente le décrypteur et attend la clé (`wait_for_key`/`key_received`) sans jamais la manipuler lui‑même en clair.

Pour un titre protégé par Widevine, les « mêmes appels » que l’app officielle mènent à une **demande de licence Widevine**, dont la clé est consommée à l’intérieur du DRM de la plateforme : les clés vivent dans le CDM, pas dans Orbit. La couche d’attestation d’intégrité décrite dans la [note sur la session](session-et-acces.md#couche-de-défi-et-dattestation) intervient en amont, à l’octroi des jetons.

**Ce que la musique a réellement emprunté sur le Pixel.** La présence des deux générations dans le binaire ne dit pas laquelle s’exécute. Les essais sur l’appareil tranchent pour la musique de ce compte : tous les décodeurs ouverts par Spotify étaient des décodeurs AAC ou MP3 **ordinaires**, sans aucun codec sécurisé ni session MediaDrm, y compris pour des titres du catalogue joués à froid et pour un titre en Lossless. Le chemin observé est donc le chemin hérité où le moteur déchiffre lui-même, pas le Widevine de plateforme. La machinerie Widevine du binaire existe mais ne s’est pas engagée pour ces lectures ; son déclencheur (contenu vidéo, sorties particulières, autre marché ou appareil) reste à identifier. Détail et limites de cette mesure en boîte noire dans la [validation sur le Pixel](validation-pixel.md#déchiffrement-observé-en-process-plutôt-que-widevine).

**Pour Spotifeur.** Pour la musique observée, le déchiffrement de l’audio suit le modèle hérité que librespot reproduit. La **couche de session et d’attestation** décrite dans la [note sur la session](session-et-acces.md#couche-de-défi-et-dattestation) intervient, elle, à l’octroi des jetons, en amont de la clé audio de la musique standard.

Le schéma exact négocié pour un titre donné — génération historique ou Widevine selon le contenu et le compte —, la durée de vie de la licence et le lien précis entre autorisation, cache et décodeur restent à établir sur un flux observé. La présence d’un même algorithme dans un autre module ou dans un client tiers ne résout pas ces questions.

| Côté décodage et sortie | Vérification |
| --- | --- |
| Vorbis | Appel et traitement d’échec associés à `vorbis_synthesis_init failed` à `0x120122c`. Le binaire contient aussi des diagnostics Vorbis pour fMP4. |
| FLAC | Descripteurs de formats 16/24 bits, diagnostics d’état FLAC et chaînes de format dans l’ELF. L’offre Premium documente jusqu’à 24 bits/44,1 kHz. |
| MediaCodec | `MediaCodecFileDecompressor` (`classes3.dex`) ouvre un fichier avec `MediaExtractor`, sélectionne un décodeur par MIME, transmet les buffers et renvoie les buffers décodés par JNI. L’ELF importe également `AMediaCodec_*`. Le chemin Java inspecté porte sur un décompresseur de fichier. |
| AudioTrack | `AudioDriver` (`classes2.dex`) construit un `AudioTrack`, écrit via `AudioTrack.write(ByteBuffer, int, int)` dans `write(byte[], int)` et signale les underruns via un callback natif. `AudioTrackAdapterImpl` (`classes10.dex`) expose routage, état et ID de session. |
| Oboe | `OboeRenderer` (`classes10.dex`) reflète les callbacks de création/destruction, route, pause et flush. Références natives à `onAudioTrackCreated` et `onAudioTrackDestroyed` autour de `0x11f4040`, callback `oboeAaudioDataCallback` à `0x11f8fc0`, chaînes de chargement `libaaudio.so` et dépendance ELF `libOpenSLES.so`. Le backend effectif dépend du chemin choisi sur l’appareil. |

## 5. Points de comparaison externes

Le guide Media Delivery de Spotify pour son eSDK matériel décrit un pipeline qui sépare transfert, décodeur et sortie. Il décrit aussi une page de métadonnées Spotify au début du flux Ogg/Vorbis livré par cet eSDK, contenant une table de recherche qui permet de convertir un temps en position dans le fichier. Le binaire Android possède un diagnostic de résolution de position qui dépend de la longueur totale de l’en-tête (`0x1200ec8`) et une référence à `_seek_table` (`0x16611f8`). Ces indices donnent des points d’entrée pour suivre un seek Android. La structure des octets livrés au build Android reste à vérifier sur un flux. [Spotify Media Delivery API](https://developer.spotify.com/documentation/commercial-hardware/implementation/guides/media-delivery).

Le code public de librespot expose les mêmes catégories générales, avec une résolution de fichier distincte du transfert CDN par plages et une demande de clé gérée par sa session. La version de son code consultée utilise toutefois une route de résolution sans `v2`, alors que cette version Android construit le préfixe `storage-resolve/v2/files/audio/`. Les choix propres à librespot concernant ses requêtes, délais et fichiers de démarrage servent de points de comparaison pour le build Android. [Résolution et transfert dans librespot](https://github.com/librespot-org/librespot/blob/dev/core/src/spclient.rs), [gestion des clés dans librespot](https://github.com/librespot-org/librespot/blob/dev/core/src/audio_key.rs).

## 6. Reproduire les observations

À exécuter dans un dossier d’analyse contenant `base.apk` et `split_config.arm64_v8a.apk`, extraits de l’APKM décrit dans le rapport. `llvm-objdump` et `llvm-readelf` proviennent de LLVM. `apkanalyzer` provient des Android SDK Command-line Tools.

```sh
unzip -p split_config.arm64_v8a.apk lib/arm64-v8a/liborbit-jni-spotify.so > orbit.so
shasum -a 256 orbit.so
llvm-readelf -h -SW -d -n orbit.so
llvm-objdump -d --start-address=0xdb8a40 --stop-address=0xdb8d74 orbit.so
llvm-objdump -d --start-address=0xdb0550 --stop-address=0xdb0ee8 orbit.so
llvm-objdump -d --start-address=0x14d5d74 --stop-address=0x14d60cc orbit.so
dd if=orbit.so bs=1 skip=$((0x5b8ab4)) count=$((0x5b8bb9-0x5b8ab4)) 2>/dev/null |
  protoc --decode=google.protobuf.FileDescriptorProto google/protobuf/descriptor.proto
apkanalyzer dex code --class p.tuz base.apk
apkanalyzer dex code --class p.vpp0 base.apk
apkanalyzer dex code --class p.oqp0 base.apk
apkanalyzer dex code --class com.spotify.playbacknative.AudioDriver base.apk
apkanalyzer dex code --class com.spotify.playbacknative.OboeRenderer base.apk
apkanalyzer dex code --class com.spotify.playbacknative.MediaCodecFileDecompressor base.apk
```

REA a aussi décompilé `AudioDriver.write([BI)I` sur une projection contenant le manifeste et `classes2.dex` d’origine (SHA-256 du DEX `6f75b6523f9c11d4de3cefa3209d9d408c61e07caaa12d42155b3c9141b0b532`). Preuve `ev_0d460738ef04cad5087a65e3f3ed5c1f061f4dc92d2d15ab8f8b7288bfa86394`. Le smali de l’APK complet confirme l’appel au pilote Android.

Les références ARM64 sont vérifiables en reconstruisant la page chargée par `ADRP` et en ajoutant l’immédiat `ADD` pour retrouver l’adresse de chaîne. Les débuts de procédures cités sont recoupés avec `.eh_frame_hdr` lorsque disponibles. Le désassemblage utilise les adresses brutes car LLVM peut afficher le nom d’un symbole exporté voisin sur du code sans symbole propre. Pour les autres descripteurs, remplacer la plage de `dd` par la plage indiquée ci-dessus.

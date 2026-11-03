# Validation sur le Pixel

Essais du 3 novembre 2026 sur le Pixel 10 Pro, Android 17 (API 37), ARM64, via ADB et avec la session déjà ouverte dans Spotify. Le téléphone utilise **Spotify 9.1.94.1929, build 147600575**. L’APKM du rapport statique correspond à **9.2.0.26, build 148373686**. Les essais sur le téléphone attestent du comportement de la première version dans les conditions ci-dessous.

Les quatre APK installés ont été extraits et leurs signatures ont été vérifiées. Leur certificat signataire correspond à celui de l’APKM. Le SHA-256 du `base.apk` installé est `78fb6f7b031a0943b98e504d37d792e1b724d642dfc09f8c0633777d2bce5d33`. Le SHA-256 d’Orbit est `e7b71a4ce817b6f38b3498aea4de37e9669d072b0c2f4621de96ef26f6a96099`, Build ID `463ffdb9ed155010`.

## Ce qui a été observé

| Scénario | Résultat |
| --- | --- |
| Fichier local, témoin indiqué par l’utilisateur | Les commandes de lecture, de déplacement et de pause ont changé l’état Android. La position publiée est passée à 87 529 ms après le déplacement. Le journal montre un décodeur Android AAC (`audio/mp4a-latm`) et l’ouverture d’une sortie AAudio de type Legacy pendant ce scénario. |
| Ouverture à froid d’un album du catalogue | Après l’arrêt du processus, `am start -W` a indiqué `COLD` et 219 ms pour le lancement de l’activité. Le premier titre a été sélectionné sur la page de l’album. Le premier échantillon d’état après la commande, à +0,96 s, indiquait `PLAYING` et une position de 319 ms. Le temps de lancement de l’activité et le délai jusqu’au premier échantillon audio sont des mesures distinctes. [Kind Of Blue](https://open.spotify.com/album/1weenld61qoidwYuZ1GESA). |
| Déplacement dans un titre du catalogue | Une commande vers 72 % du titre a fait publier une position de 393 482 ms et un état `PLAYING` au premier relevé, +0,93 s après la commande. Le titre tournait déjà depuis plusieurs dizaines de secondes. La capture ne permet pas d’attribuer le résultat à un cache ni à une nouvelle plage CDN. |
| Commande Suivant | Le titre a changé et le nouvel état publié à +0,90 s indiquait `PLAYING` à 329 ms. Spotify a sélectionné un élément de la file manuelle du compte, qui passe avant la suite de l’album. Ce scénario valide la commande dans la session existante. La transition naturelle est mesurée plus bas. |
| Playlist communautaire | L’ouverture de son URI dans Spotify a affiché les titres. Le premier titre, « Some Other Time », a démarré et le premier relevé à +1,21 s indiquait `PLAYING`. Cette observation concerne le client officiel et le compte présent sur le Pixel. [Playlist publique](https://open.spotify.com/playlist/1n9XPCVFLTuXP9Kk3mOPhj). |
| Lecture téléchargée en mode hors connexion Spotify | L’option a été activée dans Spotify. Après arrêt et relance à froid du processus, l’accueil affichait le mode hors connexion et un album préalablement téléchargé. La sélection d’un titre marqué comme téléchargé a donné `PLAYING` à +1,03 s. Le titre visible correspondait au média de la session Android. Le Wi-Fi du téléphone est resté actif pour ADB. Le mode en ligne de Spotify a été rétabli et vérifié après l’essai. |
| Transition naturelle | « Blue in Green » lancé depuis l’album, déplacement à 94 %, puis fin du titre sans commande. Le titre suivant venait de la file manuelle du compte, prioritaire sur la suite de l’album. MediaSession a publié `BUFFERING` à 0 ms, puis `PLAYING` à 164 ms **0,48 s plus tard**. La sortie AAudio de Spotify est restée `started` pendant tout le passage. |
| Perte de réseau limitée à Spotify | Réseau de Spotify seul coupé par le pare-feu Android 32 s après le lancement d’un mouvement de 35 min 32 jamais joué sur ce compte (Bruckner, Symphonie n° 8, Adagio, Celibidache). La lecture a continué **641 s** sans réseau, puis est passée en `BUFFERING` à **672 403 ms** (11 min 12). Après le rétablissement, `PLAYING` est revenu **37 s** plus tard, à la même position. Un premier essai sur « Flamenco Sketches » a tenu toute la fenêtre de 290 s, et un déplacement à 7 min 30 a fonctionné hors ligne. Une partie de ce titre était probablement déjà en cache (voir plus bas). |

Les positions sont les valeurs publiées par Android MediaSession. Entre deux événements, `dumpsys media_session` peut répéter une position de référence même si la lecture avance. Le champ `buffered position` est resté à zéro dans les relevés. Il ne permet pas de calculer l’avance du tampon Spotify. Les mesures et actions figurent dans le [relevé des états du lecteur](preuves/pixel-etats-lecteur.csv).

AAudio a également été ouvert pendant le scénario du catalogue. Des configurations MediaCodec ont été journalisées pendant la navigation et les essais, dont `audio/mpeg`. Le journal ne lie pas chaque instance de décodeur au titre en cours. Le codec du flux principal du titre du catalogue reste donc indéterminé.

## Trafic observé

Perfetto a échantillonné `android.network_packets` sur le téléphone, puis la requête SQL a filtré sur l’UID Android de Spotify. Les lignes suivantes portent sur TCP vers le port distant 443, sur des fenêtres délimitées par les commandes ADB. Les nombres comprennent les paquets du réseau attribués à Spotify et leurs en-têtes.

| Fenêtre | Durée | Octets reçus | Octets émis |
| --- | ---: | ---: | ---: |
| Lecture du fichier local avant le déplacement | 6,53 s | 20 976 | 18 760 |
| Ouverture de l’album avant lecture | 5,29 s | 391 865 | 58 330 |
| Après la commande de lecture du premier titre de l’album | 10 s | 5 705 808 | 60 390 |
| Déplacement dans le titre du catalogue déjà en lecture | 3,86 s | 7 454 | 6 032 |
| Lecture du premier titre de la playlist tierce | 5,02 s | 2 462 204 | 33 357 |
| Lecture d’un titre téléchargé en mode hors connexion Spotify | 4,19 s | 112 | 684 |

**Ces volumes sont des minorants.** Sur une même fenêtre de 15,5 s au lancement d’un titre neuf, le compteur `wlan0` de `/proc/net/dev` a reçu 13 256 941 octets, alors que Perfetto n’attribuait que 3 663 986 octets à l’ensemble des UID, dont 3 662 830 à Spotify. La ligne de base de `wlan0`, Spotify en pause, était d’environ 7 ko par 15 s. La source `android.network_packets` sous-compte donc d’un facteur d’environ 3,6 sur ces rafales. Elle reste utile pour situer les échanges dans le temps et les attribuer à l’UID. Les tailles absolues proviennent de `wlan0`. [Relevé `wlan0`](preuves/pixel-wlan0-rafales.csv).

Le témoin local montre que le processus peut communiquer même quand la source audio est un fichier. Le volume reçu au début des lectures du catalogue est bien supérieur sur ces essais. Le relevé ne sépare pas les octets audio des pochettes, métadonnées, préchargements et autres échanges. Il ne donne ni le débit d’encodage ni la taille des requêtes par plage. Les faibles volumes observés en mode hors connexion Spotify ne remplacent pas une mesure avec la connectivité Android coupée. [Données agrégées](preuves/pixel-trafic-agrege.csv), [source Perfetto](https://perfetto.dev/docs/analysis/stdlib-docs#android.network_packets).

## Déchiffrement observé en-process plutôt que Widevine

Le binaire porte deux générations de protection ([détail](audio-natif.md#4-autorisation-média-et-décodage)) : un chemin hérité où le moteur déchiffre lui-même, et un chemin Widevine où la clé reste dans le DRM de la plateforme. Les essais sur le Pixel départagent ce qui s'exécute réellement pour la musique.

Mesure fiable, par le service système `dumpsys media.metrics`, filtrée sur l'UID de Spotify et cumulée sur toute la session d'essais :

| Décodeur plateforme utilisé par Spotify | Occurrences | MIME |
| --- | ---: | --- |
| `c2.android.aac.decoder` | 5 | `audio/mp4a-latm` |
| `c2.android.mp3.decoder` | 8 | `audio/mpeg` |
| Codec sécurisé (`.secure`) ou MediaCrypto | 0 | — |

Tous les décodeurs ouverts par Spotify sont des décodeurs **ordinaires**, y compris pour des titres du catalogue joués à froid (Bruckner, Mahler, Schubert). Aucun décodeur sécurisé ni session MediaCrypto n'apparaît. Or un flux chiffré en CBCS/CENC passé à MediaCodec via Widevine exigerait un codec sécurisé et une session MediaDrm, qui seraient visibles ici. Le titre joué en **Lossless** (Bach, Cello Suite n° 1, libellé « Lossless » confirmé à l'écran) n'a produit aucun décodeur plateforme : Spotify décode le FLAC dans son propre code.

La lecture la plus directe est donc que **le moteur Spotify déchiffre la musique dans son propre code, puis remet des trames en clair à un décodeur AAC ou MP3 de plateforme**, ou décode lui-même le FLAC. C'est le modèle hérité, celui que librespot reproduit, et non le Widevine de plateforme.

Limites honnêtes de cette observation en boîte noire. `dumpsys media.metrics` journalise l'usage des codecs mais pas le déchiffrement interne au process. Une tentative de lire `/proc/<pid>/maps` pour confirmer l'absence de bibliothèque DRM chargée a échoué (`Permission denied`, sans root) : cette piste n'est pas retenue. L'observation établit que la musique jouée sur cet appareil et ce compte n'a pas emprunté le décodeur sécurisé ni de session DRM de plateforme. Elle n'exclut pas qu'un autre contenu (vidéo, certaines sorties, un autre marché ou appareil) déclenche le chemin Widevine présent dans le binaire. Le déclencheur exact de ce chemin reste à identifier.

## Tampon, transition et perte de réseau

Ces essais ont été menés en Wi-Fi avec la qualité de streaming réglée sur **Élevée, 160 kbit/s**. L’écran de réglage précise que le changement de qualité s’applique au titre suivant, sauf si du contenu téléchargé ou en cache existe en qualité supérieure.

| Mesure | Résultat |
| --- | --- |
| Rafale initiale d’un titre neuf | 13,26 Mo reçus sur `wlan0` en **1,1 s** au lancement d’un mouvement de 26 min 02 (Mahler, Symphonie n° 9, IV, Rattle). Le total n’a augmenté que de 22 ko pendant les 55 s suivantes. Un second titre neuf a donné 13,26 Mo en 15,5 s. |
| Couverture de la rafale | À 160 kbit/s, 13,26 Mo représentent environ 11 min d’audio. La lecture sans réseau de Bruckner s’est arrêtée à 11 min 12, ce qui concorde. Pour le mouvement de Mahler, d’environ 31 Mo à ce débit, la rafale ne couvre qu’environ 43 % du fichier. Le client remplit donc un tampon d’environ 13 Mo, pas le fichier entier. |
| Pas de rechargement en continu | Après la rafale, Perfetto n’enregistre plus de transfert notable pour Spotify : quelques ko par tranche de 5 s jusqu’à la fin de Blue in Green, et aucun octet pendant les 30 s qui ont précédé la coupure de Bruckner. Le seuil qui déclenche un rechargement n’a pas été atteint pendant ces essais. |
| Transition naturelle | Seulement 21,6 ko pendant les 19 s qui précèdent le passage et 24,0 ko pendant les 10 s qui suivent (Perfetto). Aucune rafale n’est apparue entre le lancement de Blue in Green et le passage au titre suivant. Ce titre était donc prêt sans téléchargement visible. L’essai ne permet pas de dire s’il était déjà en cache sur le compte ou si son début faisait partie de la rafale initiale. |
| Reprise après la perte | Le trafic reprend au rétablissement : 3,26 Mo mesurés par Perfetto en 38 s, minorant. Le retour à `PLAYING` intervient 37 s après le rétablissement. Le seul `BUFFERING` publié pendant la coupure est celui de l’arrêt, MediaSession ne signale pas d’erreur (`error=null`). |
| Titres déjà joués | « Flamenco Sketches » (9 min 26, environ 11 Mo à 160 kbit/s) n’a reçu que 3,7 Mo au lancement selon Perfetto, mais a été joué plus de 5 min hors ligne avec un déplacement à 7 min 30. Avec le facteur de sous-comptage, cela reste compatible avec un tampon de 13 Mo. Un cache antérieur sur le compte n’est pas exclu. |

Conséquences pour Spotifeur avec App Remote. Une coupure de quelques minutes ne s’entend pas pendant un titre déjà lancé. L’interface ne doit donc pas tirer de l’état réseau Android une conclusion sur la lecture. L’état utile est celui que publie Spotify : `BUFFERING` à l’épuisement du tampon, puis une reprise qui peut demander plusieurs dizaines de secondes après le retour du réseau. L’indicateur de chargement doit suivre cet état, sans interrompre la commande en cours. Le comportement d’App Remote lui-même pendant la coupure, sa connexion locale et ses événements, reste à mesurer avec un Client ID.

Une capture complémentaire des catégories Cronet `netlog` et `net`, limitée au producteur Spotify et avec filtrage des annotations, n’a produit aucune tranche exploitable lors de l’ouverture d’un album. La trace par UID ne donne pas les hôtes, les URL, les en-têtes HTTP ou le mécanisme de demande de clé. [Traçage Cronet](https://chromium.googlesource.com/chromium/src/+/HEAD/components/cronet/tracing.md).

## Recoupement avec l’APKM

| Élément | Adresse dans Orbit sur le Pixel | Observation |
| --- | --- | --- |
| Résolveur de stockage | `0xd7ae38`, référence `0xd7aecc` | Le constructeur du chemin charge `storage-resolve/v2/files/audio/`. Il référence aussi les modes `offline`, `interactive_prefetch` et `interactive`. |
| Réponse du résolveur | `0x5a0f38..0x5a103d` | Descripteur Protobuf identique octet pour octet à celui de l’APKM analysé. |
| Requête par plage | `0xd7262c`, référence `0xd72d04` | La procédure qui journalise les bornes charge `Range`. |
| Validation de plage | `0x142a7f0`, référence `0x142a8cc` | La procédure de réponse charge `Content-Range`. |
| Début de fichier | `0xd754d8` | La même procédure référence les branches de lecture du cache et de demande CDN. |
| Préchargement de clé et transition | `0xc6adf0` et `0xf59dd4` | Références aux diagnostics de clé préchargée et de transition vers une piste préparée. Les diagnostics ne sont pas apparus dans les journaux des essais. |
| Édition de file | `0x615f88..0x6161c1` | Le descripteur `es_set_queue_request.proto` est identique à celui de l’APKM. |
| Téléchargements | `0x5e9ef4..0x5ec91f` | Le service `Offline` conserve l’ajout, le retrait, la progression et les états de licence. La méthode `SubscribeDownloadSessions` figure dans le descripteur de l’APKM 9.2.0.26 et manque dans celui du Pixel. |

Ces références proviennent du désassemblage ARM64 du binaire installé. Le rapprochement confirme la présence des mécanismes dans les deux builds. Les traces système ne permettent pas d’identifier quelle branche de résolution ou de gestion de clé a exécuté chaque lecture.

## Périmètre tiers restant

La lecture dans Spotify, la présence de titres de playlist communautaire, le hors-ligne natif, la transition naturelle et la tenue du tampon pendant une perte de réseau sont vérifiés pour le client officiel. Le comportement d’**App Remote** et les réponses de la **Web API** restent à mesurer avec un client de test enregistré dans Spotify for Developers, son empreinte Android et ses propres autorisations. La session du téléphone suffit aux scénarios Spotify de cette page. Elle ne fournit pas les scopes d’une intégration tierce. Il reste notamment à tester la reconnexion App Remote après perte de réseau, le `403` Web API sur la même playlist et le contrôle d’un téléchargement depuis App Remote. [Matrice SDK](sdk-spotify.md), [configuration App Remote](https://developer.spotify.com/documentation/android/tutorials/getting-started).

## Reproduction des mesures

La capture utilise `android.network_packets` avec `poll_ms: 100`, `aggregation_threshold: 0` et un tampon de 16 Mio. L’UID est relevé avec `adb shell dumpsys package com.spotify.music`. Les actions et relevés sont marqués par le premier champ de `/proc/uptime`. La requête ci-dessous sélectionne les événements qui entrent dans une fenêtre en nanosecondes de temps depuis le démarrage.

```sql
INCLUDE PERFETTO MODULE android.network_packets;

SELECT direction, sum(packet_count) AS paquets, sum(packet_length) AS octets
FROM android_network_packets
WHERE socket_uid = :spotify_uid
  AND packet_transport = 'IPPROTO_TCP'
  AND remote_port = 443
  AND ts >= :debut_ns AND ts < :fin_ns
GROUP BY direction;
```

La coupure réseau limitée à Spotify utilise la chaîne de pare-feu de débogage d’Android 17. Le reste du téléphone et ADB restent connectés. Rétablir les deux réglages à la fin de l’essai.

```sh
adb shell cmd connectivity set-chain3-enabled true
adb shell cmd connectivity set-package-networking-enabled false com.spotify.music
# … mesure …
adb shell cmd connectivity set-package-networking-enabled true com.spotify.music
adb shell cmd connectivity set-chain3-enabled false
```

Le volume absolu est lu dans la colonne de réception de `wlan0` de `/proc/net/dev`, lisible sans root, avant et après l’action. L’état de la sortie audio est lu dans `dumpsys audio`, sur la ligne `type:AAudio u/pid:<uid Spotify>/…`, qui passe de `started` à `paused`. L’essai de perte de réseau s’arrête quand MediaSession ou AAudio quitte la lecture.

Les fichiers publiés dans le dossier de preuves contiennent les agrégats et des états de lecteur sans identifiants de compte ni titres privés. Le téléchargement était déjà présent sur le téléphone. La bascule hors connexion a été rétablie dans son état initial.

# Catalogue, recherche, playlists et bibliothèque

Le build 148373686 expose des interfaces Java annotées pour certaines vues réseau et des services natifs pour la synchronisation et l’hydratation du catalogue. Les éléments ci-dessous distinguent les annotations et sites d’appel DEX, les références à des chaînes dans l’ELF et les schémas embarqués. Les noms obfusqués sont propres au build.

Pour Spotifeur, la Web API fournit la recherche et les fiches par ID. App Remote expose des recommandations et des actions sur des URI connues. La [matrice SDK](sdk-spotify.md) donne les routes publiques retenues et leurs limites.

## Recherche et fiches

`p.g370.b` (`classes2.dex`) convertit les annotations `Lp/ay30;`, `Lp/wyq0;` et `Lp/yyq0;` en GET, POST et PUT. La table combine ce branchement avec les annotations des interfaces et les appels directs du bytecode. Les offsets après le nom d’une méthode sont des unités de code DEX à partir de son premier opcode.

| Fonction | Route annotée | Interface et appelant |
| --- | --- | --- |
| Recherche | GET `searchview/v3/search` | `p.rd51.a/d` dans `classes11.dex`, appelées par `p.vi11.Q +0x33` et `p.vi11.R +0x4e` dans `classes7.dex`. Variante réactive `Single<MainViewResponse>` et variante suspendue. |
| Vue native et suggestions | GET `searchview/v3/native`, GET `searchview/v3/autocomplete` | `p.rd51.c/e`, appelées par `p.uw41.invoke +0x11/+0x23` dans `classes11.dex`. |
| Autocomplétion typée | GET `searchview/v3/autocomplete` | `p.kea1.a` appelée par `p.m021.l +0x62`, même DEX. `l` construit la carte avec `query`, `locale`, `entity_types` séparés par des virgules, `limit`, `request_id` et `timestamp`. Cette preuve porte sur ce chemin d’autocomplétion. |
| Fiche titre | GET `track-entity-view/v2/track/{id}` | `p.rm30.a` dans `classes11.dex`, appelée par `p.nay.apply +0x32` dans `classes9.dex`. `p.sm30.a` dans ce dernier DEX référence également une variante Cosmos `hm://track-entity-view/v2/track/`. |
| Onglet à propos de l’artiste | GET `creatorabout/v0/artist/{artistId}/about` | `p.gxn.a/b` dans `classes5.dex`, appelées notamment par `p.qva.x0`. Les deux variantes diffèrent par la projection de champs, dont `artistFacts` dans une des deux. |
| Résolution d’URI de playlist | GET `playlist/v2/resolve-uri/{uri}` | `p.dss0.a` dans `classes2.dex`, appelée par `p.fsc0.invokeSuspend +0x25` dans `classes8.dex`. |

Des constructeurs du client HTTP chargent `https://spclient.wg.spotify.com/` (`p.py30.b`, `classes.dex`, et `p.sqe.B`, `classes7.dex`). Cette base et les interfaces annotées établissent les familles de services. Elles ne démontrent pas la sélection de l’hôte pour chaque requête, les paramètres obligatoires côté serveur ni le contenu des réponses dans chaque région.

La signature de `p.rd51.a` a été confirmée avec REA sur une projection locale contenant le manifeste et le `classes11.dex` original, renommé `classes.dex`. Le DEX d’origine a pour SHA-256 `f994a60a684dd0e02567afa439a28e2717edaa372d749511c369e0431d3dac22`. La méthode est abstraite. REA a relevé quatre classes référençant l’interface dans ce DEX, dont `p.uw41`. Les appels issus d’autres DEX ont été contrôlés séparément. Preuves REA `ev_8987e742a0acd2a30692683c55b4ccacc0a6da478f2da5e2e04c1a6f3c40a695` et `ev_fc74bf056ef642b2e62245dd72b04280102c8df76f58f2dad93d777b8a0b2358`.

## Hydratation des entités

Dans `liborbit-jni-spotify.so`, la procédure `0xcc574c` charge `extended-metadata/v0/extended-metadata`, `extended-metadata/v3/extensions/batch-get` et `extended-metadata/v3/configs/get-all`. Les instructions `ADRP` commencent respectivement à `0xcc6248`, `0xcc6318` et `0xcc6614`. Leur regroupement établit la présence de chemins v0 et v3, sans attribuer de route à tous les écrans ou à tous les comptes.

Le descripteur `extended_metadata.proto` (`0x66df3c..0x66e52d`) structure le lot en un en-tête et des requêtes par entité. `BatchedEntityRequest.header = 1`, `entity_request = 2` répété. L’en-tête prévoit pays et catalogue. `EntityRequest.entity_uri = 1` et `query = 2` répété. Chaque `ExtensionQuery` porte `extension_kind = 1` et un `etag = 2`. Le retour agrège des extensions par type, dont l’en-tête distingue erreur fournisseur, TTL de cache et TTL hors-ligne. Il existe donc un découplage de modèle entre identifiant d’entité, projection demandée, réponse d’extension et cache. Un résultat de recherche peut fournir l’entité à hydrater. La séquence effective entre recherche et hydratation dépend de la vue et du cache.

`metadata.proto` (`0x6713c0..0x6737f4`) définit `Artist`, `Album` et `Track`. L’album contient notamment artistes, disques, couvertures et disponibilité. Le titre contient son album, ses artistes, ses restrictions, ses variantes et ses alternatives. La bibliothèque contient également des projections `spotify.cosmos_util.proto.ArtistMetadata` et `AlbumMetadata` (`0x639560` et `0x63931c`). La première porte lien, nom et portraits. La seconde porte artistes, lien, nom, couvertures et année. La présence de ces projections n’identifie pas le chemin réseau exact de chaque écran album ou artiste.

La seconde passe a aussi confirmé le chemin de **vue artiste dans la collection**. Dans `classes.dex`, `p.r5h.d(CollectionGetArtistViewRequest)` appelle `ClientBase.callStream` avec `spotify.collection_esperanto.proto.CollectionService` et `StreamArtistView`. Le parseur `p.vgp1.apply` (`classes12.dex`, `+0x31d`) décode la réponse. Le descripteur natif `collection_get_artist_view_request.proto` (`0x5aa764..0x5aaa81`) décrit une autre projection, dans l’espace `spotify.collection_cosmos.proto`, avec URI d’artiste et politiques de projection artiste, album et titre. L’ELF référence aussi `data.collection.artist_view` à `0xc66204`. Cela précise le parcours lié à la collection. La recherche ciblée dans les quinze DEX a retrouvé `mobile-album-page` dans `p.ip31.j` (`classes9.dex`, `+0xa`). Le smali rattache ce nom à une structure d’instrumentation de deeplink et d’interaction de lecture. Cette occurrence ne constitue pas une route de données d’album. La source des sections éditoriales, notamment les titres populaires d’un artiste, reste à établir.

## Playlists

L’ELF assemble le préfixe `playlist/v2/` à `0x13917e8`. Le même bloc référence `GET` avec `/changes` et `/diff`, les champs `revision`, `hint_revision` et `handlesContent`, et un en-tête `x-accept-list-items` dont la valeur contient `audio-track`. À `0x13908b4`, un autre bloc charge `playlist/v2/user/` et les fragments `/rootlist`, `/changes` et `/diff`. La liste racine de l’utilisateur et le contenu d’une playlist ont donc des chemins distincts dans ce binaire.

La couche Java possède en parallèle `POST playlist/v2/playlist/{playlist_id}/changes` via `p.efe.a` (`classes10.dex`) avec un corps `ListChanges`, et `PUT playlist/v2/playlist/{playlist_id}/items` via `p.hrh1.a` (`classes3.dex`) avec un corps `ListUpdateRequest`. Le premier a un appel direct depuis `p.wfu0.apply +0x1f`, le second depuis `p.xfu0.apply +0x1a`, tous deux dans `classes10.dex`. Le verbe GET natif de lecture des changements et le POST Java d’écriture des changements sont deux observations différentes.

Le schéma `playlist_api.proto` (`0x63ca58..0x63f67d`) sépare les éléments, la pagination, la révision et les permissions.

| Message | Champs structurants |
| --- | --- |
| `Item` | `uri = 1`, `attributes = 2` |
| `ListItems` | `pos = 1`, `truncated = 2`, `items = 3` répété, `continuation_token = 6` |
| `SelectedListContent` | `revision = 1`, `length = 2`, `contents = 5`, `diff = 6`, `up_to_date = 10`, `owner_username = 16`, `capabilities = 18`, `geoblock = 19`, `changes_require_resync = 20` |
| `ListChanges` | `base_revision = 1`, `deltas = 2`, `nonces = 6` |
| `ListUpdateRequest` | `base_revision = 1`, `attributes = 2`, `items = 3`, `info = 4` |

Le service Esperanto `playlist_get_request.proto` (`0x5e29bc`) décrit une lecture avec URI, requête et politique, ainsi qu’une lecture groupée et un délai. L’existence du service et du transport HTTP ne démontre pas une correspondance univoque entre leurs requêtes. Pour les playlists communautaires, le paquet établit le même modèle d’URI, de contenu, de révision et de capacités. Le critère exact qui permet la lecture d’une playlist tierce et le détail des rôles collaborateurs restent à confirmer par cas. Le compte et la visibilité sont des entrées importantes. En API publique, le mode développement restreint la lecture des éléments aux listes possédées ou collaboratives pour l’utilisateur. [Get Playlist Items](https://developer.spotify.com/documentation/web-api/reference/get-playlists-items), [guide de migration](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide).

## Permissions et dossiers de playlists

La seconde passe a identifié un constructeur de chemin distinct de Playlist4. La procédure ARM64 `0x13b0464` charge `playlist-permission/v1/playlist/` à `0x13b0548` et ajoute `/permission` à `0x13b0560`. Elle a aussi des branches pour d’autres types d’entités, dont `list/`. Le descripteur `playlist_permission.proto` (`0x642284..0x644b4d`) distingue les capacités `can_view = 1`, `can_administrate_permissions = 2`, `can_edit_metadata = 4` et `can_edit_items = 5`. Un autre bloc natif charge une erreur commune pour playlist introuvable ou interdite (`0x10b128c`). Ces preuves établissent le découplage entre lecture de liste et permissions dans le client officiel. La règle serveur applicable à chaque playlist tierce reste ouverte. Le `403` public se traite selon le contrat Web API, même quand App Remote peut lancer le contexte par URI. [Matrice SDK](sdk-spotify.md), [Get Playlist Items](https://developer.spotify.com/documentation/web-api/reference/get-playlists-items).

Dans `your_library_request.proto` (`0x5d7c9c..0x5d837f`), `YourLibraryRequestHeader` expose `folder_id = 16`, `all_playlists = 17` et `fill_folders = 34`. L’en-tête de réponse (`0x5d770c..0x5d7b67`) expose `folder_name = 15` et `parent_folder_uri = 20`. Les procédures `0x100fc54` et `0x100ffcc` référencent des diagnostics distincts pour la requête et la projection des dossiers. Le chemin de mutation est distinct. `p.o130.a/b/c` (`classes8.dex`) prépare les opérations `create`, `remove` et `set`, puis appelle `p.jn7.O`. Ce dernier envoie la méthode Esperanto `Modify` de `RootlistModificationService`. Le descripteur `rootlist_modification_request.proto` (`0x5e2f7c..0x5e3175`) enveloppe un `folder_uri = 1` et une `ModificationRequest = 2`. Le modèle de navigation et l’adaptateur de mutation sont établis. Les conditions de réussite et le trajet réseau des mutations restent ouverts. [Fonction dans Spotify Android](https://newsroom.spotify.com/2026-05-28/playlist-folders-mobile-queue-controls-updates/).

## Bibliothèque personnelle et synchronisation

Les références ARM64 à `collection/v2/initialized` (`0x13c6a24`), `paging` (`0x13c572c`), `delta` (`0x13c50e4`) et `write` (`0x13c4e30`) sont dans le split natif. La procédure `0x13c4db4` charge explicitement `application/vnd.collection-v2.spotify.proto` pour `Accept` et `Content-Type` dans les branches delta et écriture. Elle contient des chemins distincts pour mises à jour en attente, delta nécessaire et jetons manquants. C’est un système de synchronisation par ensemble et curseurs, distinct du mécanisme de révisions de Playlist4.

Le descripteur `collection2v2.proto` (`0x601880..0x601d37`) permet de distinguer les curseurs d’une page et de synchronisation.

| Opération | Champs vérifiables |
| --- | --- |
| Initialisation | `InitializedRequest` reçoit `username = 1` et `set = 2`. `InitializedResponse.initialized = 1`. |
| Lecture paginée | `PageRequest` reçoit `username = 1`, `set = 2`, `pagination_token = 3` et `limit = 4`. `PageResponse` retourne `items = 1`, `next_page_token = 2` et `sync_token = 3`. |
| Incréments | `DeltaRequest.last_sync_token = 3`. `DeltaResponse` porte `delta_update_possible = 1`, `items = 2` et `sync_token = 3`. |
| Écriture | `WriteRequest.items = 3` et `client_update_id = 4`. La présence de l’identifiant ne suffit pas à prouver une garantie d’idempotence serveur. |
| Élément | `CollectionItem` porte `uri = 1`, `added_at = 2`, `is_removed = 3`, `context_uri = 4` et `item_data = 5`. |

Le format permet de transmettre les suppressions dans un delta et de signaler un delta indisponible. La politique exacte de rechargement après un jeton rejeté, le stockage SQLite et le traitement d’écritures concurrentes n’ont pas été établis pour ce build.

## Reproduction ciblée

Avec le même `base.apk` et la même bibliothèque native que dans [Moteur audio](audio-natif.md), les signatures d’interface et les appels peuvent être contrôlés sans lancer Spotify.

```sh
apkanalyzer dex code --class p.rd51 base.apk
apkanalyzer dex code --class p.g370 base.apk
apkanalyzer dex code --class p.m021 base.apk
apkanalyzer dex code --class p.efe base.apk
apkanalyzer dex code --class p.hrh1 base.apk
apkanalyzer dex code --class p.r5h base.apk
apkanalyzer dex code --class p.o130 base.apk
apkanalyzer dex code --class p.jn7 base.apk
llvm-objdump -d --start-address=0x139179c --stop-address=0x1391e88 orbit.so
llvm-objdump -d --start-address=0x13c4db4 --stop-address=0x13c561c orbit.so
dd if=orbit.so bs=1 skip=$((0x601880)) count=$((0x601d37-0x601880)) 2>/dev/null |
  protoc --decode=google.protobuf.FileDescriptorProto google/protobuf/descriptor.proto
```

Les extrémités des plages de descripteurs sont exclusives. Une projection DEX sert à confirmer une déclaration et les références présentes dans ce seul DEX. Les annotations et les autres sites d’appel ci-dessus ont pour référence le paquet complet.

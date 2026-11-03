# Session Spotify Android et accès pour un client tiers

Analyse statique de Spotify Android 9.2.0.26, version code 148373686, à partir du `base.apk` et du split ARM64. État des sources publiques au 3 novembre 2026.

| Artefact | SHA-256 |
| --- | --- |
| `base.apk` | `cc165669d31851b1193cae7d54c9261579c431b432080970f460704d06e4612f` |
| `split_config.arm64_v8a.apk!/lib/arm64-v8a/liborbit-jni-spotify.so` | `2353f26f5e174917b36c3c9de4538717c6348cc0bacf8b2c094f22d1447538e2` |

## Entrées Android et passage en natif

Le manifeste distingue plusieurs circuits d'entrée. `com.spotify.login.loginflowimpl.LoginActivity` est une activité interne avec l'action `com.spotify.mobile.android.service.action.session.LOGIN`. `SpotifyMainActivity` déclare les liens HTTPS de retour pour login, inscription et demande de lien de connexion sur `auth-callback.spotify.com`, ainsi qu'un filtre analogue avec le schéma `spotify-auth-music`. L'activité SSO `com.spotify.appauthorization.sso.AuthorizationActivity` est exportée et accepte entre autres `com.spotify.sso.action.START_AUTH_FLOW` et le préfixe HTTPS `accounts.spotify.com/inapp-authorize`. Un autre composant exporté, `AuthorizationCommandProxyActivity`, déclare `START_TOKENLESS_AUTH_FLOW`. Ces filtres établissent les points d'entrée du binaire. La sélection du circuit et les contrôles effectués sur les Intent dépendent du code et de l'état du compte.

Le DEX sépare la configuration d'authentification, l'accès aux comptes, la gestion OAuth et la durée de vie de la session. Les noms ci-dessous sont ceux conservés dans l'artefact.

| Ancrage DEX | Fait statique |
| --- | --- |
| `com.spotify.authentication.login5.Login5Configuration` | Champs `clientId`, `deviceId`, `protocolVersion`. Le constructeur initialise `protocolVersion` à 3 et les deux chaînes à vide. Les setters permettent de modifier ces valeurs. |
| `com.spotify.authentication.login5setupimpl.NativeLogin5SetupImpl` | `create` prend une `Login5Configuration`, un `NativeTimerManagerThread` et un `NativeApplicationScopeAPI`. `create`, `destroy` et `getNativeTransport` sont natifs. Le type de retour du dernier est `com.spotify.esperanto.esperanto.Transport`. |
| `com.spotify.authentication.oauthsetupimpl.NativeOAuthSetupImpl` | La méthode statique native `create` reçoit notamment `NativeLogin5Setup`, `AccessToken`, `UnencryptedCredentials` et `NativeRouter`. Il expose lui aussi `getNativeTransport`. `AccessToken` transporte une chaîne de jeton, son type et un `expiresAt` de type `long`. |
| `com.spotify.authentication.accountssetupimpl.NativeAccountsSetupImpl` | Construction native à partir d'`AccountsConfiguration` et de `NativeApplicationScopeAPI`. |
| `com.spotify.connectivity.auth.NativeSession` | `createNativeSessionWithoutAp` accepte notamment `NativeApplicationScope`, `UnencryptedCredentials` et `NativeOAuthSetup`. La classe expose un nom canonique, un transport interne, `tryReconnectNow`, `prepareForShutdown` et `blockingLogout`. |
| `com.spotify.authentication.tokenexchangesetupimpl.NativeTokenExchangeSetupImpl` | Construction native liée à `NativeAuthenticatedScopeAPI`, `NativeAccountsSetup` et `NativeOAuthSetup`. REA retrouve des classes Esperanto distinctes pour `WebAuthTokenRequest` et `SessionTransferTokenRequest`. |

Trois sites d'appel précisent le montage de cette session. Les offsets sont des offsets fichier des DEX extraits.

1. Dans `classes3.dex`, `p.xwn1.run()V` crée un `NativeConnectivityManager`, un `PrefsCredentialsStorage` et les fournisseurs de type et politique de connexion. Il les raccorde au `NativeApplicationScope` avec le routeur Cosmos et `NativeTrafficSetup`. Il renseigne ensuite une `Login5Configuration` à partir de `ApplicationScopeConfiguration`, impose la valeur `3` à `0x656506`, puis appelle `NativeLogin5SetupImpl.create` à `0x65651a`. Le résultat est conservé dans `p.mcl.O0`. Les deux versions présentes dans l'ELF n'impliquent donc pas un usage indifférencié des deux.
2. Dans `classes4.dex`, `p.jmn0.run()V` reprend ce `NativeLogin5SetupImpl` et construit `UnencryptedCredentials` à partir du nom et d'un tableau d'octets issu de l'objet de connexion. Il crée un `AccessToken` de type `Bearer`, emploie une chaîne vide en l'absence de jeton et divise `Date.getTime()` par 1000 pour remplir `expiresAt`, avec 0 si l'expiration manque. Il impose aussi le protocole 3 à `0x48c69a`, appelle `NativeOAuthSetupImpl.create` à `0x48c744` et enveloppe le transport natif dans `SchedulingTransport` sur un scheduler RxJava 3.
3. Dans `classes2.dex`, `p.g54.run()V` forme les justificatifs de session à partir du même modèle, récupère le `NativeApplicationScope` et le `NativeOAuthSetupImpl`, appelle `createNativeSessionWithoutAp` à `0x265d32`, puis conserve le résultat dans `p.ph61.g`.
4. Dans deux autres branches du même `run()V`, `NativePubSubSetupV2Impl.create` reçoit le transport Esperanto, le timer, OAuth et le scope application à `0x265dc2`. `NativeAuthenticatedScope.create` reçoit la session et l'objet pub/sub précédemment construit à `0x265fd2`. Les dépendances statiques sont donc visibles à la frontière JNI.

`createNativeSessionWithoutAp` est donc effectivement appelée par du code du DEX. Le natif contient aussi un résolveur et des types associés à Access Point. L'usage de l'un ou l'autre pour une opération précise reste à établir. Esperanto est ici le type de transport exposé à la frontière JNI. Son usage côté Java ne suffit pas à attribuer un protocole réseau ou un serveur à chaque appel.

**REA Android.** `search-android-classes` avec `com.spotify.authentication` fournit l'inventaire et son ancrage sur le SHA-256 de l’APK sous l'Evidence ID `ev_06ee9a96b9bb037dc42b04f6c6486c38c4e906635a0b9174c906ad225395b98a`. Le serveur REA connecté a signalé l'absence de `REA_JADX_MCP_JAR`. La recherche a abouti via REA CLI 6.7.0, JDK 21 et le JAR JADX headless 0.7.1 de SHA-256 `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`. Les appels REA ciblés sur la classe et les références ont dépassé le délai du fournisseur. Les signatures et sites d'appel sont vérifiés dans le smali rendu par `apkanalyzer dex code --class <classe> base.apk`.

## Routes natives et rôles des jetons

Les adresses ci-dessous sont des adresses virtuelles ELF avant relocalisation. Pour les sections concernées, elles coïncident avec les offsets fichier. Les paires d'instructions AArch64 `ADRP` puis `ADD` chargent effectivement les adresses des littéraux, ce qui donne une preuve plus précise que leur seule présence dans le binaire.

| Rôle | Littéral et offset | Référence de code vérifiée |
| --- | --- | --- |
| Découverte de connectivité | `https://apresolve.spotify.com/v2` à `0x39517d` | `0x137a728–0x137a72c`. Cette branche est précédée d'un test de configuration et une autre branche rejoint le même bloc. |
| Hôte Webgate présent dans le binaire | `https://spclient.wg.spotify.com` à `0x359e07` | `0xb52e88–0xb52e8c` et `0xb52ea0–0xb52ea4`. |
| Jeton client | `https://clienttoken.spotify.com/v1/clienttoken` à `0x3bd3c8` | `0xb7f8ec–0xb7f8f0`. Le même bloc charge `application/x-protobuf` à `0xb7f924–0xb7f928` depuis `0x456e4e`. |
| Authentification Login5 v3 | `https://login5.spotify.com/v3/login` à `0x3bd9b5` | `0xba6cac–0xba6cb0`. `Content-Type` et `application/x-protobuf` sont chargés respectivement à `0xba6cc4` et `0xba6cd0`. |
| Authentification Login5 v4 | `https://login5.spotify.com/v4/login` à `0x3bd9fc` | `0xba70cc–0xba70d0` et `0xbac4f4–0xbac4f8`. `application/x-protobuf` est chargé à `0xba70ec` dans le premier bloc. |
| Autorisation Accounts | `https://accounts.spotify.com/oauth2/device/authorize` à `0x375258`, et `https://accounts.spotify.com/oauth2/v2/auth` à `0x3e644e` | Références à `0xb9f440`, `0xba2770` et `0x1619eb8` pour la première, à `0xc53f0c` pour la seconde. `https://accounts.spotify.com/api/token` figure aussi à plusieurs offsets de `.rodata`. |

Deux familles de jetons sont distinctes dans le binaire.

- Le **jeton client** a son propre contrat Protobuf. Le `FileDescriptorProto` compilé dans `protodesc_cold` couvre exactement les octets `[0x59bb94, 0x59c882)` et se décode comme `spotify/client_token/v0/client_token_http.proto`, paquet `spotify.clienttoken.http.v0`. `ClientTokenRequest` comporte un type et une branche de données client ou de réponses à des défis. `ClientTokenResponse` comporte un type, une branche d'octroi et une branche de défis. Dans `GrantedTokenResponse`, les champs sont `token` n°1 (`string`), `expires_after_seconds` n°2 (`int32`), `refresh_after_seconds` n°3 (`int32`) et `domains` n°4 (messages `TokenDomain` répétés). Le littéral d'en-tête `client-token` à `0x4555a8` est chargé à `0x13761e0` et `0x1376400`. Le schéma expose un périmètre de domaines et des temporisations indépendantes de la session utilisateur. Il ne détermine pas la variante de défi choisie pour un appareil donné ni la politique effective d'envoi du jeton.
- Le **jeton de session** apparaît dans les types Login5 v3 et v4 via `LoginOk.access_token` à `0x34be2c` et `0x3571b5`. Les messages de diagnostic de `Login5OAuthClient` distinguent une valeur expirée ou absente du cache (`0x35f1fa`), une récupération réseau (`0x36e83d`), un recul avant nouvelle tentative (`0x392daf`), un renouvellement forcé après changement de produit (`0x38764b`) et un changement du justificatif enregistré (`0x36ae14`). `NativeOAuthSetup` contient un chemin de rotation et de persistance d'un justificatif chiffré, comme l'indiquent les diagnostics contigus à `0x34c158` et `0x34c196`. Ces chaînes rendent visibles les états prévus. Les conditions d'activation, durées et clés de stockage demandent une analyse supplémentaire.

### Couche de défi et d'attestation

Les deux familles de jetons ne se demandent pas en clair. Elles passent par un défi que le client doit résoudre, et le binaire montre plusieurs couches.

- **Preuve de travail hashcash.** Le contrat ClientToken déclare `spotify.clienttoken.http.v0.HashCashParameters` et `HashCashAnswer`, avec les symboles natifs `evaluate_hashcash_parameters` et `evaluateHashcashParameters` et le champ `prefix` dans les paramètres. Login5 déclare les mêmes primitives à ses versions v3 et v4 : `spotify.login5.v3.challenges.HashcashChallenge`/`HashcashSolution` (`0x160b7f0`, `0x160b860`) et leurs équivalents v4 (`0x160c910`, `0x160c980`). Le serveur renvoie un préfixe et une difficulté, le client cherche un suffixe dont le condensat satisfait la difficulté, puis renvoie la réponse. Ce mécanisme est connu et reproductible : il n'identifie pas un appareil et ne lie pas la requête à une app signée. Il impose seulement un coût de calcul.
- **Autres défis Login5.** À côté du hashcash, les descripteurs portent des défis de code à usage unique et de redirection : `CodeChallenge`/`CodeSolution.code`, `Finish.nonce`, `Redirect.interaction_url`, `InteractionSolution.interact_ref`. Les diagnostics `auth_challenge_not_found`, `ChallengeNotSet`, `Nonce mismatch` et `mismatched nonce` encadrent la validation. Le choix du défi dépend du compte et de l'état côté serveur.
- **Jetons liés par DPoP.** Le binaire référence `session_dpop_missing_nonce` et `spotify.authenticated_sessions.connect_authorization.proto.AuthInfo.DpopBoundAuthCodeInfo.pkce_code_challenge`. Une partie des jetons est donc liée à une clé par preuve de possession (DPoP), et non simplement porteuse. Le symbole `ed25519PublicKeyFromSeed` situe une clé Ed25519 côté client. Rejouer un jeton capté ne suffit alors pas sans la clé de liaison.
- **Jetons liés par DPoP.** Le binaire référence `session_dpop_missing_nonce` et `DpopBoundAuthCodeInfo.pkce_code_challenge`. Une partie des jetons est liée à une clé par preuve de possession (DPoP), non simplement porteuse. Rejouer un jeton capté ne suffit pas sans la clé de liaison.

#### Contrat ClientToken décodé

Les deux descripteurs ClientToken sont compilés dans Orbit. `client_token.proto` (`spotify.clienttoken.serialization.v0`) décrit le jeton stocké : `token`, `expires_at_timestamp`, `refresh_at_timestamp`, `domains`. `client_token_http.proto` (`spotify.clienttoken.http.v0`) décrit l'échange avec `clienttoken.spotify.com`.

| Message | Champs décodés |
| --- | --- |
| `ClientTokenRequest` | `request_type` (enum), `client_data` (`ClientDataRequest`), `challenge_answers` (`ChallengeAnswersRequest`), `client_data_derived_value` (bytes n°10), `client_data_derived_value_token` (bytes n°11). |
| `ClientDataRequest` | `client_version`, `client_id`, `connectivity_sdk_data` (`spotify.clienttoken.data.v0.ConnectivitySdkData`), `current_token`, `client_data_derived_value_version`. |
| `Challenge` | oneof `client_secret_parameters` (`ClientSecretParameters.salt`), `evaluate_js_parameters` (`EvaluateJSParameters.code`, `libraries[]`), `evaluate_hashcash_parameters` (`HashCashParameters.length`, `prefix`). |
| `ChallengeAnswer` | oneof `client_secret` (`ClientSecretHMACAnswer.hmac`), `evaluate_js` (`EvaluateJSAnswer.result`), `hash_cash` (`HashCashAnswer.suffix`). |
| `ClientTokenResponse` | `granted_token` (`GrantedTokenResponse`) ou `challenges` (`ChallengesResponse.state`, `challenges[]`). |

Le serveur impose donc l'un de trois défis : un **HMAC à secret client** (le client applique un HMAC avec un secret embarqué sur un sel fourni), un **EvaluateJS** où le serveur pousse du code JavaScript et des bibliothèques que le client doit exécuter pour renvoyer un `result`, ou un **hashcash**. Le hashcash est reproductible et public. L'EvaluateJS est dynamique : le serveur peut pousser à tout moment du code obfusqué, qu'un client tiers devrait exécuter fidèlement ; c'est une preuve d'exécution par le vrai client. Le HMAC dépend d'un secret que le binaire protège.

**Empreinte d'appareil transmise.** `ConnectivitySdkData` porte `device_id` et un `PlatformSpecificData` dont la variante `NativeAndroidData` déclare : `os_version`, `api_level`, `name`, `model`, `brand`, `manufacturer`, `screen_dimensions`, `volume_steps`, **`installer_name`** (le magasin qui a installé l'app, signal d'app secondairement installée ou reconditionnée) et un champ opaque `device_data_ext`. Le serveur reçoit donc une description signalétique de l'appareil et de l'origine d'installation à chaque demande de jeton.

#### Attestation : Google Play Integrity

Ce proto ne contient pas de jeton d'attestation : l'attestation est un sous-système distinct, côté DEX, qui s'appuie sur **l'API Google Play Integrity**. Les DEX référencent `StandardIntegrityManager.prepareIntegrityToken`, `PrepareIntegrityTokenRequest{cloudProjectNumber=…}`, `requestExpressIntegrityToken`, `warmUpIntegrityToken` et `StandardIntegrityTokenRequest{requestHash=…}`, avec les rappels `OnRequestIntegrityTokenCallback` et `PlayIntegritySessionPlugin`. Un module Spotify dédié, `com.spotify.integrity.integrityimpl`, expose un RPC `VerifyRequest`/`VerifyResponse` et une configuration `AndroidIntegrityProperties`. Côté ClientToken, `EsClientToken.EncryptedClientToken` montre que le jeton peut être chiffré. Côté natif, le `AppIntegrityLoggerSetupImpl` et `app_integrity_delayed_request` journalisent et transmettent le signal, de façon différée. Le binaire porte en plus, dans l'espace de noms `spotify::drm`, une fonction `collectFridaFeatures()` et le diagnostic `frida_detect: passive detection triggered (debug build, not enforced)` : un dispositif anti-instrumentation désactivé sur cette build de debug mais présent.

Le fonctionnement de Play Integrity est public et n'est pas propre à Spotify. L'app demande à Google Play Services un **verdict d'intégrité signé par Google**, calculé sur un `requestHash` et lié à l'identité Play de l'application — nom de paquet `com.spotify.music`, certificat de signature et `cloudProjectNumber` — ainsi qu'à l'intégrité de l'appareil. Sur ce Pixel, cette intégrité remonte à une racine d'attestation matérielle Google Tensor, visible dans la chaîne de certificat du CDM Widevine relevée pendant les essais. Le serveur Spotify vérifie ce verdict auprès de Google. Le verdict est donc lié au binaire officiel distribué par Google Play, signé du certificat de Spotify, et à un appareil certifié Play avec des services Google authentiques.

#### Le défi EvaluateJS et la détection d'altération

Parmi les trois défis, **EvaluateJS** est le plus résistant à une réimplémentation, parce qu'il est dynamique. Le message `EvaluateJSParameters` ne porte que `code` (source JavaScript) et `libraries` (bibliothèques référencées) ; la réponse `EvaluateJSAnswer` ne porte que `result`. Le serveur peut donc pousser à tout moment du code arbitraire, vraisemblablement obfusqué et sondant l'environnement d'exécution, et n'accepter que le résultat exact du vrai client. Un client tiers devrait exécuter fidèlement ce code dans un environnement équivalent, sans en connaître la logique à l'avance.

Ce que l'analyse statique établit sur son exécution côté Android reste limité, et il faut le dire tel quel. Le contrat des trois défis est partagé entre plateformes et compilé dans Orbit ; l'acquisition du jeton est pilotée nativement par `auth::client_token::ClientTokenAcquirer`. Mais la bibliothèque native **ne contient aucun moteur JavaScript** ni routine d'évaluation JS, et les DEX ne référencent pas le sandbox dédié `androidx.javascriptengine`. Une `android.webkit.WebView` avec `evaluateJavascript` et `addJavascriptInterface` existe dans l'application, sans lien démontré avec le défi ClientToken. L'exécuteur d'un défi EvaluateJS sur cet Android n'est donc pas identifié ici. Le hashcash, lui, a des primitives dédiées et reste le chemin le plus simple et le plus probable par défaut sur mobile ; EvaluateJS paraît davantage orienté vers les environnements dotés d'un runtime JavaScript, comme le client web, ou serait délégué à la WebView si le serveur l'exigeait. Ce point précis demande une observation en exécution pour être tranché.

Autour de ces défis, l'application embarque une détection d'environnement altéré qui complète Play Integrity : une bibliothèque native `librootChecker.so`, et le SDK anti-fraude **Ravelin** (`com.ravelin.core.util.security.RootCheckerNative`) qui recherche `com.topjohnwu.magisk` et `eu.chainfire.supersu`. Avec l'anti-instrumentation `collectFridaFeatures` du moteur, ces contrôles visent le même objectif que l'attestation : établir que le client s'exécute dans un environnement non modifié.

**Synthèse de la couche de session.** La résolution du catalogue, la session Login5 et le hashcash sont des contrats visibles et reproductibles. À l'octroi du jeton client interviennent l'attestation Play Integrity, le défi dynamique EvaluateJS, le HMAC à secret embarqué et les jetons liés par DPoP. Le verdict d'intégrité est produit dans les services Google et le TEE de l'appareil, pas dans l'APK ; l'analyse statique en donne la structure. C'est l'ensemble des mécanismes qui, côté session, distingue l'app signée par Spotify des autres clients.

`X-Spotify-Connection-Id` est chargé à `0x13737fc` depuis `0x3aa60f`. La signature textuelle du constructeur `WebgateSetupImpl` à `0x387379` mentionne à la fois `http::ConnectionBuilder`, une configuration Webgate et un `pubsub::ConnectionIdProvider`. Cela étaye l'association entre certaines requêtes HTTP et un identifiant de connexion de pub/sub. Le même bloc de configuration charge `wss://dealer.g2.spotify.com:443` à `0xb52e74` avant l'hôte Webgate à `0xb52e88`. Le diagnostic de réception d'un identifiant WebSocket est référencé à `0xbd0370` et le nom `connection_id_decorator` à `0x1372dac`. Le bloc de résolution référence aussi les catégories `accesspoint`, `spclient` et `dealer-g2` à `0x137a818`, `0x137a848` et `0x137a878`. Côté DEX, `NativeApplicationScope` expose des transports Esperanto distincts pour les hôtes, le stockage d'authentification et Login5. `NativeAuthenticatedScope.create` reçoit explicitement `NativeSession` et `NativePubSubSetup`. La portée exacte de cet en-tête, les versions HTTP négociées et l'ordre d'établissement des transports restent ouverts.

### Configuration réseau Android

Le manifeste référence `0x7f160012`, résolu en `res/xml/network_security_config.xml`. Le fichier définit un `base-config` permissif pour le trafic clair et une surcharge qui l'interdit pour `spotify.com`, `spotify.net`, `spotifyinternal.com`, `scdn.co` et d'autres domaines listés, sous-domaines compris. L'arbre XML ne contient pas de `pin-set`. Cette observation est limitée à cette déclaration Android. Le comportement des implémentations natives et la validation TLS réelle ne se déduisent pas de ce seul fichier.

Reproduction sur les mêmes artefacts avec les outils Android et LLVM. Pour relire le schéma, passer la tranche d'octets indiquée à `google.protobuf.descriptor_pb2.FileDescriptorProto.FromString`.

```sh
apkanalyzer manifest print base.apk
apkanalyzer dex code --class com.spotify.connectivity.auth.NativeSession base.apk
apkanalyzer dex code --class p.xwn1 --method 'run()V' base.apk
apkanalyzer dex code --class p.jmn0 --method 'run()V' base.apk
apkanalyzer dex code --class p.g54 --method 'run()V' base.apk
aapt2 dump xmltree base.apk --file res/xml/network_security_config.xml
llvm-objdump -d --start-address=0xba6ca4 --stop-address=0xba6cdc liborbit-jni-spotify.so
llvm-objdump -d --start-address=0xba70c4 --stop-address=0xba70f8 liborbit-jni-spotify.so
```

Les descripteurs ClientToken et `ConnectivitySdkData` sont décodés depuis les `FileDescriptorProto` compilés dans la bibliothèque, à partir de la marque de nom (`\n<longueur>client_token.proto`, `\n<longueur>connectivity.proto` suivi du paquet `spotify.clienttoken.data.v0`), puis parcours du format wire. Les classes d'attestation sont relevées côté DEX.

```sh
unzip -o base.apk 'classes*.dex' -d dex/
for d in dex/classes*.dex; do strings -n 6 "$d"; done | grep -iE 'StandardIntegrity|requestExpressIntegrityToken|cloudProjectNumber|spotify/integrity/integrityimpl'
```

## Spotify Connect : état de lecture et contrôle des appareils

Au-dessus du transport `dealer` se trouve la surface back-end qui synchronise l'état de lecture entre appareils et transporte les commandes de contrôle. C'est la couche que pilote App Remote et celle qu'un client natif devrait parler pour apparaître comme appareil, publier son « en cours de lecture », recevoir des commandes et transférer la lecture. Les descripteurs sont compilés dans Orbit : `connect.proto` et `player.proto` (paquet `spotify.connectstate`), `devices.proto` (`spotify.connectstate.devices`).

**Transport.** Le client ouvre le WebSocket `wss://dealer.g2.spotify.com:443`, d'où il reçoit un `X-Spotify-Connection-Id`. Il publie ensuite son état par une requête d'état vers `connect-state/v1/devices/<device_id>` sur Webgate, portant cet identifiant de connexion. Le serveur renvoie un `Cluster`, l'état partagé du compte. Les commandes arrivent ensuite par le WebSocket, à des routes comme `connect-state/v1/player/command/from/<expéditeur>` pour les commandes de lecture, et `connect-state/v1/connect/transfer|volume|rename/from/<id>` pour le transfert, le volume et le renommage. Le natif journalise la logique de renvoi (`ConnectStateClientImpl PutStateBackoff`, `allow_concurrent_put_states`, `putstate response with … cluster update reason was …`). Les points `hm://connect-state/v1/connect/logout|proxy_wake|proxy_transfer|volume` et `…/group/add-device|remove-device` et `connect-state/v1/cluster/wake-devices` complètent l'ensemble.

**Ce que le client publie (`PutStateRequest`).** `callback_url`, un `device` (lui-même `device_info` + `player_state` + `transfer_data`), `member_type`, `is_active`, `put_state_reason`, `message_id`, `last_command_sent_by_device_id`, `has_been_playing_for_ms`, `client_side_timestamp` et `only_write_player_state`. Le champ `is_active` et le couple `message_id`/`last_command_message_id` servent l'accusé de réception des commandes.

**Comment le client se décrit (`DeviceInfo` + `Capabilities`).** `DeviceInfo` porte `can_play`, `volume`, `name`, `device_software_version`, `device_type`, `spirc_version`, `device_id`, `client_id`, `brand`, `model`, `license`, `is_group`, `audio_output_device_info` et une carte de métadonnées. `Capabilities` déclare le rôle et le périmètre : `can_be_player`, `is_controllable`, `is_observable`, `supports_transfer_command`, `supports_command_request`, `command_acks`, `supported_types[]`, `supports_playlist_v2`, `volume_steps`, `supports_gapless`, `supports_crossfade`, `supports_hifi`, `supported_audio_quality`, `supports_set_options_command`. Un client peut ainsi se présenter comme **contrôleur** sans être lecteur (`is_controllable` et requêtes de commande, `can_be_player` à faux) : il pilote alors l'appareil Spotify actif, ce que fait App Remote, mais par le protocole back-end.

**Ce que le serveur renvoie (`Cluster`, `ClusterUpdate`).** `Cluster` expose `active_device_id`, le `player_state` courant, la carte des appareils (`device`), `transfer_data`, `need_full_player_state`, `needs_state_updates` et `started_playing_at_timestamp`. Les mises à jour arrivent en `ClusterUpdate` avec un `update_reason`, un `ack_id` et la liste `devices_that_changed`.

**Le modèle d'état (`PlayerState`).** Il sépare `context_uri` et `context_url`, l'origine de lecture (`play_origin`), l'index dans le contexte, le `track` courant (`ProvidedTrack`), `position_as_of_timestamp`, `duration`, les drapeaux `is_playing`/`is_paused`/`is_buffering`, les `options` (lecture aléatoire, répétition), les listes `prev_tracks` et `next_tracks`, `queue_revision`, `playback_quality` et les identifiants `playback_id`/`session_id`. C'est le même modèle que celui exposé côté lecture dans la [note audio](audio-natif.md).

Ces contrats sont établis statiquement par les descripteurs embarqués. La chorégraphie exacte — ordre d'établissement, cadence des `PutState`, forme précise des trames de commande sur le WebSocket et accusés — demande une observation en exécution. Comme le reste des services internes, Connect n'est atteint qu'après la session authentifiée et l'attestation décrites plus haut.

## Interfaces officielles et conditions d’accès

La [matrice d’intégration](sdk-spotify.md) met en correspondance App Remote, Auth, Web API et les preuves tirées de l’APK. App Remote utilise l’autorisation `app-remote-control` pour la connexion locale. Pour la Web API, le flux mobile retenu est Authorization Code avec PKCE. Il demande ses propres scopes, vérifie `state`, échange le code avec le `code_verifier` et actualise le jeton selon le flux documenté. Spotify exige une URI de retour HTTPS pour la Web API, sauf HTTP sur une adresse IP de bouclage littérale. [Autorisation Android](https://developer.spotify.com/documentation/android/tutorials/getting-started), [PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), [URI de retour](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri), [actualisation](https://developer.spotify.com/documentation/web-api/tutorials/refreshing-tokens).

En mode développement, Spotify exige un abonnement Premium actif du propriétaire de l'application et limite une nouvelle intégration à cinq utilisateurs autorisés. Depuis juillet 2026, un compte développeur peut créer jusqu'à 25 Client IDs en mode développement, qui partagent son quota. Un dépassement de ce quota est signalé par `429` et `reason=QUOTA_EXCEEDED`. Pour les nouvelles demandes de quota étendu, Spotify demande notamment une organisation enregistrée, un service lancé et au moins 250 000 utilisateurs actifs mensuels. [Modes de quota](https://developer.spotify.com/documentation/web-api/concepts/quota-modes), [mise à jour de juillet 2026](https://developer.spotify.com/blog/2026-07-23-web-api-quota-updates)

Spotify documente aussi un eSDK pour le matériel commercial, avec une API Media Delivery destinée à ses partenaires, ainsi que Spotify Soloist, un lecteur sans interface pour Linux, macOS Apple Silicon et Windows x86-64. Ces offres ont leurs propres plateformes d’intégration. [eSDK matériel](https://developer.spotify.com/documentation/commercial-hardware), [Spotify Soloist](https://developer.spotify.com/documentation/soloist).

## Couverture

| Question | État |
| --- | --- |
| Points d'entrée Android et séparation comptes, Login5, OAuth, session et échange | Établis pour les déclarations et signatures listées. |
| Présence des routes Login5 v3/v4, ClientToken et résolution dans le code | Établie par références AArch64 aux chaînes. |
| Contrat ClientToken | Messages, types de défi (HMAC à secret, EvaluateJS, hashcash) et empreinte d'appareil décodés depuis les descripteurs embarqués. Paramètres et décisions serveur en exécution non observés. |
| Attestation d'intégrité | Établie comme Google Play Integrity (Standard/Express) par les classes DEX et le module `com.spotify.integrity.integrityimpl`. Le format exact du verdict transmis et son point d'injection précis dans la demande de jeton restent à confirmer en exécution. |
| Spotify Connect (état et contrôle) | Contrats `PutStateRequest`, `Cluster`, `DeviceInfo`, `Capabilities` et `PlayerState` décodés depuis les descripteurs, transport `dealer` et routes `connect-state/v1` relevés. Chorégraphie et trames de commande en exécution non observées. |
| Démarrage à froid, session reprise, repli réseau | Les types et diagnostics exposent plusieurs chemins. Leur ordre effectif et les conditions d'activation demandent des observations de runtime. |
| Autorisation d'un flux musical via un Client ID tiers | Le dossier public établit l'accès aux données et au pilotage. La compatibilité des jetons publics avec les services internes de l'APK n'a pas été testée. |

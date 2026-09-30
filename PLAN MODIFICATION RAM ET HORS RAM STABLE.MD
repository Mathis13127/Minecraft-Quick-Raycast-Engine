Architecture de Manipulation de Terrain Asynchrone (Zéro-Corruption & Zéro-Lag)

Ce document définit la nouvelle norme pour QVE. L'objectif est d'abandonner définitivement les méthodes vanilla lentes (setBlock) et l'écriture brute instable sur les fichiers .mca. Le système se divise en deux stratégies distinctes selon l'état du chunk (chargé ou déchargé).

1. Stratégie RAM (Chunks chargés) : L'Injection par Swap de Pointeur
Problème actuel : Appeler setBlock depuis un worker crash le serveur et déclenche des mises à jour inutiles (physique, lumière, réseau).
Solution (Inspirée d'Axiom) : Remplacer les données en mémoire de manière atomique.

Étape 1 (Worker Thread) - Le Calcul Isolé : Le worker QVE calcule la géométrie et construit une nouvelle instance de PalettedContainer<BlockState> dans son coin. Aucune interaction avec le Level vanilla à ce stade.

Étape 2 (Server Thread) - L'Injection Atomique : Le worker envoie une micro-tâche à la file d'attente du serveur (server.execute(...)). Cette tâche fait une seule chose : elle écrase l'ancienne référence du tableau de blocs par la nouvelle (chunkSection.states = nouveauContainer). L'opération prend un cycle d'horloge.

Étape 3 (Server Thread) - La Synchronisation : Dans cette même micro-tâche, le serveur marque le chunk comme sale (chunk.setUnsaved(true)) et force l'envoi d'un ClientboundLevelChunkWithLightPacket aux joueurs environnants pour mettre à jour leur affichage.

Résultat : Des millions de blocs modifiés en mémoire instantanément, 0 lag côté serveur, 0 ConcurrentModificationException.

2. Stratégie Hors-RAM (Chunks déchargés) : L'Injection IOWorker
Problème actuel : Modifier l'en-tête .mca manuellement depuis QVE provoque des collisions fatales si le serveur sauvegarde un autre chunk au même moment.
Solution (Inspirée de FastAsyncWorldEdit) : Sous-traiter l'écriture sur le disque au gestionnaire de fichiers natif de Mojang.

Étape 1 (Worker Thread) - La Lecture Sécurisée : Le worker lit le fichier .mca pour extraire le CompoundTag (NBT) du chunk ciblé. La lecture parallèle ne corrompt jamais les données.

Étape 2 (Worker Thread) - La Modification NBT : Toujours en asynchrone, le worker modifie les tableaux long[] (les bits des blocs compressés) à l'intérieur du NBT et re-sérialise le tout dans un objet CompoundTag propre. Le chunk n'est jamais instancié en objet Java lourd.

Étape 3 (L'Injection) - Fire and Forget : Le worker transmet ce NBT au thread d'I/O vanilla (IOWorker / ChunkStorage.write()).

Étape 4 (IOWorker Vanilla) - L'Écriture Sécurisée : Le thread natif de Minecraft prend le relais. C'est lui qui verrouille le fichier, met à jour l'en-tête de 8 Ko séquentiellement, gère la fragmentation des secteurs et compresse le chunk (Zlib/LZ4).

Résultat : 100 % d'intégrité garantie sur les fichiers régions, aucune corruption d'en-tête, et le worker QVE n'est jamais bloqué par le disque dur.

3. Plan d'Action Technique (Checklist de développement)
Pour implémenter cette architecture, ton développeur devra créer des points d'accès spécifiques (Mixins ou Access Wideners) :

Accès RAM : Créer un Mixin dans LevelChunkSection pour exposer ou permettre le remplacement (setter) de la variable privée contenant le PalettedContainer.

Notification Réseau : Créer un utilitaire pour forcer l'envoi du ClientboundLevelChunkWithLightPacket (ou utiliser les méthodes de ChunkMap vanilla) après l'injection RAM.

Accès I/O (Disque) : Identifier le point d'entrée exact du gestionnaire de stockage de NeoForge/Vanilla (généralement RegionFileStorage ou ChunkStorage) pour pouvoir appeler la méthode asynchrone write(ChunkPos, CompoundTag).

Ton développeur comprendra immédiatement la logique : on laisse Mojang faire le sale boulot de sécurité réseau et d'écriture disque, et on garde la puissance brute des mathématiques dans les workers QVE.
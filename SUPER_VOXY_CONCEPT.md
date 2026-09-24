# SUPER VOXY : ARCHITECTURE & CONCEPT DU MOTEUR VOXEL LOD GPU

**Document de Conception Technique**  
**Auteur :** Antigravity & Mathis13127  
**Cible :** Minecraft 1.21.1 (NeoForge) / Écosystème Flywheel & Create  
**Statut :** Spécification Conceptuelle  

---

## 1. VISION & OBJECTIFS

**Super Voxy** est un moteur de rendu de terrain lointain (LOD - *Level of Detail*) en voxels purs, capable d'afficher des mondes Minecraft déchargés à des distances extrêmes (**64, 128, 256, voire 512 chunks**) avec une fluidité absolue (> 200 FPS).

### Pourquoi les solutions existantes (Voxy, Distant Horizons) souffrent :
1. **Goulot d'étranglement NBT :** Elles s'appuient sur les parseurs NBT conventionnels de Mojang (`NbtIo`, `CompoundTag`). Chaque région Anvil décompressée génère des centaines de milliers d'objets Java temporaires et de chaînes de caractères (`String`), provoquant des pics d'allocation et des freezes du Garbage Collector (GC).
2. **Surcharge mémoire :** Elles maintiennent souvent des structures de données trop riches ou des doubles couches de stockage.
3. **Temps de génération des LODs :** Le passage de la géométrie de bloc brute au maillage GPU prend plusieurs secondes par région.

### La rupture technologique de Super Voxy :
Super Voxy repose directement sur notre cœur voxel bas niveau ultra-optimisé :
* **Zero-Allocation Stream Reader :** Décodage binaire direct des fichiers `.mca` sans créer le moindre tag NBT intermédiaire.
* **Palette Unpacker sans division CPU :** Décompression instantanée des blocs en 2 cycles CPU.
* **Empreinte mémoire division par 100 :** 2 à 4 Ko par chunk au lieu de 200 Ko à 1 Mo en mémoire vanilla.
* **Extraction de maillage vectorisée (Greedy Meshing Bitwise) :** Génération des quads visibles de surface en moins d'une microseconde par section.
* **Pipeline d'Instanciation GPU Flywheel :** Rendu direct via VBOs instanciés et indirect draw calls sur le GPU.

---

## 2. ARCHITECTURE TECHNIQUE

```
 ┌────────────────────────────────────────────────────────┐
 │               SUPER VOXY ENGINE (GPU LOD)              │
 └──────────────────────────┬─────────────────────────────┘
                            │
       ┌────────────────────┴────────────────────┐
       ▼                                         ▼
 ┌───────────────┐                         ┌───────────────┐
 │ CPU STREAMER  │                         │ GPU PIPELINE  │
 │ (Zero-Alloc)  │                         │  (Flywheel)   │
 └───────┬───────┘                         └───────┬───────┘
         │                                         │
         ├► Fast MCA Decoder (Bytes FNV-1a)        ├► Quad Instance Buffers
         ├► BlockState [] Dynamic Registry         ├► Frustum & Hi-Z Culling
         ├► Bitwise Surface Hull Extractor         ├► Chunk Mipmaps (Octree)
         └► Zero-NBT Memory Footprint              └► Indirect Draw Calls
```

### 1. Le Streamer Disque Zero-Alloc
* Les fichiers `.mca` sont lus par streaming ou mappés en mémoire (*Memory-Mapped I/O*).
* Le parseur ignore 100 % des tags superflus (`block_ticks`, `fluid_ticks`, `PostProcessing`, `Heightmaps`, `biomes`, `entities`).
* Seule la structure `block_states` est lue.
* Les noms de blocs et propriétés `[facing=..., half=...]` sont convertis à la volée en IDs numériques denses de 16 bits (`short`) via des tables de hachage direct sans allocation de `String`.

### 2. L'Extracteur de Surface Voxel (Bitwise Culled Meshing)
Dans un chunk de $16 \times 16 \times 16$ blocs (4 096 voxels), 90 % à 95 % des voxels sont intérieurs et invisibles.
* **Algorithme Bitwise :**
  Pour chaque axe ($X, Y, Z$), on extrait la visibilité des faces par masques binaires :
  $$\text{FaceVisible}_{\text{UP}} = \text{SolidMask} \ \& \ \sim(\text{SolidMask} \gg 1)$$
* En quelques opérations bitwise, on obtient le masque exact des faces à afficher.
* **Greedy Meshing optionnel :** Fusion des quads coplanaires adjacents ayant le même block ID pour réduire le nombre de polygones de 60 % supplémentaires.

### 3. Pyramides Hiérarchiques de LOD (Voxel Mipmaps)
Pour les très grandes distances (> 32 chunks), Super Voxy agrège les voxels en octrees réguliers :
* **LOD 0 (1:1) :** Résolution 1 bloc = 1 voxel (distance 0 à 32 chunks).
* **LOD 1 (2:1) :** 1 voxel = cube de $2 \times 2 \times 2$ blocs (distance 32 à 64 chunks).
* **LOD 2 (4:1) :** 1 voxel = cube de $4 \times 4 \times 4$ blocs (distance 64 à 128 chunks).
* **LOD 3 (8:1) :** 1 voxel = cube de $8 \times 8 \times 8$ blocs (distance 128 à 512 chunks).
Chaque passage de LOD réduit le nombre de sommets à dessiner d'un facteur 8 tout en préservant la silhouette géologique du relief.

### 4. Pipeline de Rendu GPU Flywheel
* **Instance Compacte (32 bits) :**
  Chaque quad de voxel est encodé dans un entier de 32 bits :
  * `localX` (4 bits : 0..15)
  * `localY` (8 bits : 0..255)
  * `localZ` (4 bits : 0..15)
  * `face` (3 bits : NORTH, SOUTH, EAST, WEST, UP, DOWN)
  * `lodLevel` (3 bits : 0..7)
  * `textureId` (10 bits : 0..1023)
* Les buffers d'instances sont envoyés au GPU sans transformation CPU complexe. Le vertex shader de Flywheel dépaquète les coordonnées en un cycle GPU.

---

## 3. TRAITEMENT DES NBT : LA RÈGLE D'OR

1. **Zéro NBT en cache mémoire :**
   Aucun arbre NBT n'est conservé en mémoire pour les voxels de terrain. Les données spatiales sont pures, immaculées et ultra-compactes (2 à 4 Ko/chunk).
2. **Extraction NBT à la Demande (On-Demand Service) :**
   Si un outil d'inspection, un script ou un joueur veut lire le NBT d'un bloc précis :
   * Une requête directe est envoyée au lecteur disque.
   * Le fichier `.mca` est lu à l'offset exact de la section sans charger le chunk dans le monde Minecraft.
   * Seul le tag `block_entities` correspondant aux coordonnées $(X, Y, Z)$ demandées est désérialisé.
   * La mémoire est immédiatement libérée après la lecture.

---

## 4. FEUILLE DE ROUTE D'IMPLÉMENTATION (MOD DÉDIÉ)

* **Phase 1 :** Finalisation du cœur spatial unifié (`VoxelCore`) avec le registre dynamique d'états `[]` et l'extracteur NBT on-demand.
* **Phase 2 :** Module d'extraction de maillage brut (`CulledMeshExtractor`).
* **Phase 3 :** Intégration du backend de rendu Flywheel 1.0.6 (Shaders instanciés et VBOs).
* **Phase 4 :** Pipeline de génération des LODs en octree (Mipmapping 3D).
* **Phase 5 :** Culling avancé (Frustum Culling + Occlusion Culling GPU).

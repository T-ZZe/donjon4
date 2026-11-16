# Donjon — Paper 26.3

`/donjon` te téléporte dans un monde superplat (créé automatiquement, nommé `donjon`) au cœur d'une immense grotte
qui abrite trois façades monumentales à portail animé :

- **gauche : l'Enfer** (nether brick, blackstone, magma, visage démoniaque, braises)
- **centre : le Château** (pierre, rosace dorée, tours et pinacles, bannières noires)
- **droite : les Cieux** (quartz, or, halo solaire, bannières bleues)

La grotte a des parois irrégulières, des stalactites, des lianes à baies lumineuses, des cristaux bleus, de la mousse,
un sol dallé à marqueterie dorée et des piédestaux à lanternes.

- `/donjon` : entrer (la grotte est construite au premier usage, ~1 minute, sans figer le serveur)
- `/donjon quitter` : revenir à l'endroit d'où vous venez
- `/donjon rebuild` (donjon.admin) : reconstruire
- `/donjon info`

Compiler : JDK 25 + Maven, `mvn clean package` → `target/Donjon.jar` (ou GitHub Actions : `.github/workflows/build.yml`).
Configuration : `plugins/Donjon/config.yml`.
Le code n'a pas été compilé contre l'API Paper 26.3 lors de sa rédaction : seule la syntaxe a été validée.

# Plan de secours du jour J — bascule réseau NAT → pont

**Date :** 2026-08-29
**Complète :** ISC-DEPLOY §7 (procédure du jour de la soutenance).

## Pourquoi cette vérification est critique

Pour administrer la VM en mobilité et lui rendre Internet quand le réseau change,
son interface `nic1` peut être basculée en **NAT** (ADR-028 ; incident du
2026-08-29). **En NAT, les téléphones ne joignent PAS la VM** : l'interface en
pont (ISC-DEPLOY §3.2) est la seule qui donne à la VM une adresse sur le réseau
local. **Si la VM reste en NAT le jour J, la démonstration Android ne fonctionne
pas** — l'application ne peut pas atteindre `isc-core`, et les six actes tombent.

La bascule NAT → pont doit donc figurer dans la liste de vérification **de la
veille**, exécutée et confirmée avant la répétition chronométrée.

## La veille — liste de vérification réseau (à cocher)

1. [ ] **Repasser `nic1` en pont** sur l'adaptateur connecté au réseau de démo.
       Lister d'abord les adaptateurs disponibles, puis basculer :

   ```powershell
   & "C:\Program Files\Oracle\VirtualBox\VBoxManage.exe" list bridgedifs
   & "C:\Program Files\Oracle\VirtualBox\VBoxManage.exe" controlvm "UbuntuServer_24.04_VB_LinuxVMImages.COM" nic1 bridged "Intel(R) Wi-Fi 6 AX201 160MHz"
   ```

   (Remplacer `"Intel(R) Wi-Fi 6 AX201 160MHz"` par le nom exact de l'adaptateur
   du réseau de démo affiché par `list bridgedifs`. VM éteinte, préférer
   `modifyvm … --nic1 bridged --bridgeadapter1 "<nom>"`.)

2. [ ] **Vérifier que la VM a repris une adresse du réseau local** (le guest
       renouvelle le DHCP tout seul au changement de lien) :

   ```
   ssh iscvm "ip -4 -brief addr show enp0s3"
   ```
   → l'adresse doit être sur le sous-réseau du réseau de démo (ex. `192.168.x.y`),
   **pas** `10.0.2.15` (qui est l'adresse NAT).

3. [ ] **Vérifier que les téléphones joignent la VM** : depuis un téléphone
       connecté au même réseau, `ping <adresse VM>` répond, et l'application
       atteint `isc-core` (port 8080).

4. [ ] Si le réseau de la salle est indisponible ou restrictif (ISC-DEPLOY §7.3),
       basculer sur le **point d'accès personnel** préparé, puis refaire les
       étapes 1 à 3 (le pont doit être sur l'adaptateur du point d'accès).

## Le jour J — rappel

- Ne **jamais** laisser la VM en NAT pour la démonstration Android.
- L'accès d'administration reste possible par l'hôte-seul (`ssh iscvm`,
  `192.168.56.10`) **quel que soit** le mode de `nic1` — il ne dépend pas de ce
  réglage, et sert de filet pendant la bascule.
- Après la bascule en pont, si l'accès Internet d'administration est requis
  (rare le jour J), il peut manquer : le pont dépend du routage du réseau de la
  salle. Ce n'est pas bloquant pour la démonstration, qui est locale.

## Retour en NAT (après la soutenance, si besoin d'Internet en mobilité)

```powershell
& "C:\Program Files\Oracle\VirtualBox\VBoxManage.exe" controlvm "UbuntuServer_24.04_VB_LinuxVMImages.COM" nic1 nat
```
Le guest reprend `10.0.2.15` seul ; Internet, DNS et Tailscale reviennent.

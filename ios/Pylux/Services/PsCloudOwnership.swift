// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

import Foundation

/// Raw entitlement fields from Sony internal_entitlements API.
struct PsCloudEntitlement {
    let id: String
    let productId: String
    let activeFlag: Bool
    let packageType: String
    let name: String
}

enum PsCloudOwnership {
    static let pageSize = 300
    static let pageCooldownSeconds: TimeInterval = 0.1

    private struct CatalogIndex {
        var byProductId: [String: Int] = [:]
        var byConceptId: [String: Int] = [:]
    }

    static func filterOwnedPs5Games(_ entitlements: [PsCloudEntitlement]) -> [PsCloudEntitlement] {
        entitlements.filter { ent in
            guard ent.packageType == "PSGD" else { return false }
            guard ent.activeFlag else { return false }
            let pid = ent.productId
            guard !pid.hasPrefix("IP"), !pid.hasPrefix("SUB") else { return false }
            return true
        }
    }

    static func crossReferenceOwnedGames(
        filteredEntitlements: [PsCloudEntitlement],
        publicCatalog: [CloudGame],
        plusLibrarySupplement: [CloudGame] = []
    ) -> [CloudGame] {
        var catalogMap: [String: CloudGame] = [:]
        for game in publicCatalog {
            catalogMap[game.id] = game
        }
        var supplementMap: [String: CloudGame] = [:]
        for game in plusLibrarySupplement {
            supplementMap[game.id] = game
        }

        var ownedGames: [CloudGame] = []
        for ent in filteredEntitlements {
            let meta: CloudGame?
            if !ent.productId.isEmpty, let g = catalogMap[ent.productId] {
                meta = g
            } else if !ent.id.isEmpty, let g = catalogMap[ent.id] {
                meta = g
            } else if !ent.productId.isEmpty, ent.id == ent.productId,
                      let g = supplementMap[ent.productId] {
                meta = g
            } else {
                meta = nil
            }

            guard var game = meta else { continue }

            game.isOwned = true
            game.entitlementId = ent.id
            game.storeProductId = ent.productId
            ownedGames.append(game)
        }

        return ownedGames
    }

    static func mergeOwnedIntoBrowseCatalog(
        browseCatalog: [CloudGame],
        ownedCrossRef: [CloudGame]
    ) -> [CloudGame] {
        var games = browseCatalog
        var catalogIndex = buildCatalogIndex(games)

        for owned in ownedCrossRef {
            let catalogMatch = findCatalogIndexForOwned(owned, catalogIndex: catalogIndex)
            if catalogMatch >= 0 {
                var existing = games[catalogMatch]
                existing.isOwned = true
                if !owned.entitlementId.isEmpty { existing.entitlementId = owned.entitlementId }
                if !owned.storeProductId.isEmpty { existing.storeProductId = owned.storeProductId }
                games[catalogMatch] = existing
                continue
            }

            var entry = owned
            entry.isOwned = true
            registerInCatalogIndex(entry, index: games.count, catalogIndex: &catalogIndex)
            games.append(entry)
        }

        return games.sorted {
            if $0.isOwned != $1.isOwned { return $0.isOwned && !$1.isOwned }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    static func parseEntitlement(_ obj: [String: Any]) -> PsCloudEntitlement? {
        guard let id = obj["id"] as? String, !id.isEmpty else { return nil }
        let gameMeta = obj["game_meta"] as? [String: Any] ?? [:]
        let name = (gameMeta["name"] as? String) ?? id
        return PsCloudEntitlement(
            id: id,
            productId: (obj["product_id"] as? String) ?? "",
            activeFlag: (obj["active_flag"] as? Bool) ?? false,
            packageType: (gameMeta["package_type"] as? String) ?? "",
            name: name
        )
    }

    private static func buildCatalogIndex(_ games: [CloudGame]) -> CatalogIndex {
        var catalogIndex = CatalogIndex()
        for i in games.indices {
            registerInCatalogIndex(games[i], index: i, catalogIndex: &catalogIndex)
        }
        return catalogIndex
    }

    private static func registerInCatalogIndex(
        _ game: CloudGame,
        index: Int,
        catalogIndex: inout CatalogIndex
    ) {
        if !game.id.isEmpty { catalogIndex.byProductId[game.id] = index }
        if !game.conceptId.isEmpty { catalogIndex.byConceptId[game.conceptId] = index }
        if !game.entitlementId.isEmpty, game.entitlementId != game.id {
            catalogIndex.byProductId[game.entitlementId] = index
        }
    }

    private static func findCatalogIndexForOwned(_ owned: CloudGame, catalogIndex: CatalogIndex) -> Int {
        if !owned.id.isEmpty, let idx = catalogIndex.byProductId[owned.id] { return idx }
        if !owned.entitlementId.isEmpty, let idx = catalogIndex.byProductId[owned.entitlementId] { return idx }
        if !owned.storeProductId.isEmpty, let idx = catalogIndex.byProductId[owned.storeProductId] { return idx }
        if !owned.conceptId.isEmpty, let idx = catalogIndex.byConceptId[owned.conceptId] { return idx }
        return -1
    }
}

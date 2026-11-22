package com.rblxstudio

// Converte o que o RbxFile leu (.rbxl, .rbxlx, .rbxm, .rbxmx e também .rblx / .rblxm) em objetos da cena.
object PlaceImport {
    const val MAX_PARTS = 30000

    class Result(var parts: Int = 0, var objects: Int = 0, var scripts: Int = 0, var skipped: Int = 0, var hidden: Int = 0) {
        var capped = false
        fun text(): String {
            var s = parts.toString() + " peça(s), " + scripts + " script(s), " + objects + " outro(s) objeto(s)"
            if (hidden > 0) s += ", " + hidden + " invisível(is) ignorada(s)"
            if (skipped > 0) s += ", " + skipped + " item(ns) de tipo não suportado"
            if (capped) s += " (limite de " + MAX_PARTS + " peças atingido)"
            return s
        }
    }

    private val PART_CLASSES = setOf(
        "Part", "MeshPart", "WedgePart", "CornerWedgePart", "TrussPart", "SpawnLocation",
        "Seat", "VehicleSeat", "UnionOperation", "NegateOperation", "IntersectOperation"
    )
    private val CONTAINERS = setOf("Model", "Folder", "Actor", "WorldModel", "StarterPlayerScripts", "StarterCharacterScripts")
    private val OBJECT_CLASSES = setOf(
        "Script", "LocalScript", "ModuleScript", "Sound", "ScreenGui", "Frame", "TextLabel", "TextButton", "ImageLabel",
        "StringValue", "NumberValue", "BoolValue", "IntValue", "ObjectValue", "Tool"
    )
    private val COPY_KEYS = listOf(
        "Source", "Value", "Text", "SoundId", "Image", "ToolTip", "TextureId", "MeshId", "TextureID", "Volume"
    )

    // arquivo de lugar (tem Workspace) ou de modelo (só objetos soltos)
    fun isPlace(tree: List<RNode>): Boolean = tree.any { it.cls == "Workspace" }

    private fun partOf(n: RNode): PartData {
        val sz = n.vec["size"] ?: n.vec["Size"] ?: floatArrayOf(4f, 1f, 2f)
        val cf = n.cf["CFrame"]
        val c = n.col["Color3uint8"] ?: n.col["Color"] ?: n.col["Color3"] ?: floatArrayOf(0.64f, 0.635f, 0.647f)
        val p = PartData(
            n.name.ifEmpty { n.cls },
            cf?.get(0) ?: 0f, cf?.get(1) ?: 0f, cf?.get(2) ?: 0f,
            maxOf(sz[0], 0.05f), maxOf(sz[1], 0.05f), maxOf(sz[2], 0.05f),
            c[0], c[1], c[2],
            n.bool["Anchored"] ?: false
        )
        p.canCollide = n.bool["CanCollide"] ?: true
        if (n.cls == "MeshPart" || n.cls == "SpawnLocation") p.className = n.cls
        if (cf != null) {
            // rot é coluna-principal: rot[coluna * 4 + linha]; cf guarda as linhas r00 r01 r02 r10 ...
            for (row in 0..2) for (col in 0..2) p.rot[col * 4 + row] = cf[3 + row * 3 + col]
        }
        val mesh = n.text("MeshId", "MeshID")
        if (mesh.isNotEmpty()) p.props["MeshId"] = mesh
        val tex = n.text("TextureID", "TextureId")
        if (tex.isNotEmpty()) p.props["TextureID"] = tex
        val tr = n.num["Transparency"]
        if (tr != null && tr > 0f) p.props["Transparency"] = tr.toString()
        return p
    }

    private fun isHiddenTrigger(n: RNode): Boolean {
        val tr = n.num["Transparency"] ?: 0f
        val coll = n.bool["CanCollide"] ?: true
        return tr >= 0.99f && !coll
    }

    private fun objectOf(n: RNode): Inst {
        val i = Inst(n.cls, n.name.ifEmpty { n.cls })
        for (k in COPY_KEYS) {
            val v = n.text(k)
            if (v.isNotEmpty()) i.props[k] = v
        }
        for ((k, v) in n.bool) if (k == "Looped" || k == "Playing" || k == "Enabled" || k == "Visible") i.props[k] = v.toString()
        if (n.cls == "Tool" && n.kids.none { it.name == "Handle" }) {
            // Tool sem Handle no arquivo: o app exige um
            i.add(PartData("Handle", 0f, 0f, 0f, 1f, 1f, 3f, 0.64f, 0.635f, 0.647f, false))
        }
        return i
    }

    // ---------- Workspace: tudo vira peça solta (modelos e pastas são "achatados") ----------
    private fun flatten(n: RNode, out: ArrayList<PartData>, ws: ArrayList<Inst>, res: Result) {
        when {
            n.cls in PART_CLASSES -> {
                if (isHiddenTrigger(n)) { res.hidden++; return }
                if (out.size >= MAX_PARTS) { res.capped = true; return }
                out.add(partOf(n))
                res.parts++
            }
            n.cls in CONTAINERS -> for (k in n.kids) flatten(k, out, ws, res)
            n.cls == "Script" || n.cls == "LocalScript" || n.cls == "ModuleScript" -> {
                ws.add(objectOf(n))
                res.scripts++
            }
            n.cls in OBJECT_CLASSES -> {
                ws.add(objectOf(n))
                res.objects++
            }
            n.cls == "Terrain" || n.cls == "Camera" -> {}
            else -> res.skipped++
        }
    }

    // ---------- outros serviços: mantém a árvore ----------
    private fun tree(n: RNode, res: Result): Inst? {
        val inst: Inst = when {
            n.cls in PART_CLASSES -> {
                res.parts++
                partOf(n)
            }
            n.cls in CONTAINERS -> {
                res.objects++
                Inst(n.cls, n.name.ifEmpty { n.cls })
            }
            n.cls == "Script" || n.cls == "LocalScript" || n.cls == "ModuleScript" -> {
                res.scripts++
                objectOf(n)
            }
            n.cls in OBJECT_CLASSES -> {
                res.objects++
                objectOf(n)
            }
            else -> {
                res.skipped++
                return null
            }
        }
        if (inst !is PartData || n.kids.isNotEmpty()) {
            for (k in n.kids) {
                val c = tree(k, res) ?: continue
                if (n.cls == "Tool" && inst.children.any { it.name == "Handle" } && c.name == "Handle") continue
                inst.add(c)
            }
        }
        return inst
    }

    // replace = true: troca a cena inteira (lugar); false: soma ao que já existe (modelo)
    fun apply(roots: List<RNode>, replace: Boolean): Result {
        val res = Result()
        val parts = ArrayList<PartData>()
        val wsObjs = ArrayList<Inst>()
        val serviceObjs = LinkedHashMap<String, ArrayList<Inst>>()
        for (r in roots) {
            if (r.cls == "Workspace") {
                for (k in r.kids) flatten(k, parts, wsObjs, res)
            } else if (Scene.services.containsKey(r.cls)) {
                val list = serviceObjs.getOrPut(r.cls) { ArrayList() }
                for (k in r.kids) {
                    val c = tree(k, res) ?: continue
                    list.add(c)
                }
            } else {
                // modelo solto (.rbxm): vai para o Workspace
                flatten(r, parts, wsObjs, res)
            }
        }
        if (replace) Scene.clearAll()
        Scene.parts.addAll(parts)
        for (o in wsObjs) Scene.workspace.add(o)
        for ((name, list) in serviceObjs) {
            val s = Scene.services[name] ?: continue
            for (o in list) {
                if (name == "StarterPlayer" && (o.className == "StarterPlayerScripts" || o.className == "StarterCharacterScripts")) {
                    // junta com a pasta que o app já criou
                    val mine = s.children.firstOrNull { it.className == o.className }
                    if (mine != null) {
                        for (c in o.children.toList()) mine.add(c)
                        continue
                    }
                }
                s.add(o)
            }
        }
        Scene.ensureStarter()
        Physics.wakeAll()
        return res
    }
}

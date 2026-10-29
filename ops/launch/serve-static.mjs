#!/usr/bin/env node
// ─────────────────────────────────────────────────────────────────────────────
// serve-static.mjs — Serveur statique SPA pour KidzPOS.
//
// 100% HORS-LIGNE : n'importe QUE des modules natifs Node (node:http, node:fs…).
// Aucune dépendance npm, donc AUCUN `npx`/`npm install` → aucun accès internet
// requis pour servir le frontend. Remplace `npx serve` (qui télécharge `serve`).
//
// Fallback SPA : toute route client sans extension de fichier (ex: /stock,
// /users) renvoie index.html → le routeur React (BrowserRouter) prend le relais.
//
// Usage : node serve-static.mjs <dossier_dist> <port>
// ─────────────────────────────────────────────────────────────────────────────
import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { join, extname, normalize } from "node:path";

const root = process.argv[2] || process.env.DIST_DIR;
const port = Number(process.argv[3] || process.env.PORT || 3000);
const host = process.env.HOST || "0.0.0.0";

if (!root) {
  console.error("usage: node serve-static.mjs <dist-dir> <port>");
  process.exit(1);
}

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".ico": "image/x-icon",
  ".webp": "image/webp",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".ttf": "font/ttf",
  ".map": "application/json",
  ".txt": "text/plain; charset=utf-8",
  ".webmanifest": "application/manifest+json",
};

async function asFile(p) {
  try {
    const s = await stat(p);
    return s.isFile() ? p : null;
  } catch {
    return null;
  }
}

const server = createServer(async (req, res) => {
  try {
    const urlPath = decodeURIComponent((req.url || "/").split("?")[0]);
    // Anti path-traversal : on borne le chemin dans `root`.
    const rel = normalize(urlPath).replace(/^(\.\.[/\\])+/, "");
    let filePath = join(root, rel);
    if (urlPath.endsWith("/")) filePath = join(filePath, "index.html");

    let found = await asFile(filePath);
    // Fallback SPA : chemin sans extension non trouvé → index.html.
    if (!found && !extname(urlPath)) found = await asFile(join(root, "index.html"));
    if (!found) {
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end("Not found");
      return;
    }

    const body = await readFile(found);
    const type = TYPES[extname(found).toLowerCase()] || "application/octet-stream";
    // index.html non caché (récupère les nouveaux hash d'assets après rebuild) ;
    // assets hashés cachables longtemps.
    const cache = found.endsWith("index.html") ? "no-cache" : "public, max-age=3600";
    res.writeHead(200, { "Content-Type": type, "Cache-Control": cache });
    res.end(body);
  } catch {
    res.writeHead(500, { "Content-Type": "text/plain" });
    res.end("Server error");
  }
});

server.listen(port, host, () => {
  console.log(`[static] ${root} → http://${host}:${port} (offline, node built-ins only)`);
});

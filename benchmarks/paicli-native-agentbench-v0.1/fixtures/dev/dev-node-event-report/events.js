#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

function summarize(inputPath) {
  const events = fs.readFileSync(inputPath, 'utf8')
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => JSON.parse(line));

  const byType = {};
  for (const event of events) {
    const bucket = byType[event.type] || { events: 0, users: new Set() };
    bucket.events += 1;
    bucket.users.add(event.user);
    byType[event.type] = bucket;
  }

  const normalized = {};
  for (const type of Object.keys(byType).sort()) {
    normalized[type] = {
      events: byType[type].events,
      uniqueUsers: byType[type].users.size,
    };
  }
  return { totalActive: events.length, byType: normalized };
}

function main() {
  const inputPath = process.argv[2];
  const outputPath = process.argv[3];
  const report = summarize(inputPath);
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
}

main();

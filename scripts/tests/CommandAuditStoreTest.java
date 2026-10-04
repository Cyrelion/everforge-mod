package de.everforge.mod.server.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** Dependency-free filesystem regressions, also run by the CI build. */
public final class CommandAuditStoreTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("everforge-command-audit-test-");
        try {
            expiry(dir.resolve("expiry"));
            rotation(dir.resolve("rotation"));
            concurrency(dir.resolve("concurrency"));
            safety(dir.resolve("safety"));
            noCommands(dir.resolve("idle"));
            failure(dir.resolve("failure"));
            System.out.println("CommandAuditStore: " + checks + " checks passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static CommandAuditStore store(Path file) {
        return new CommandAuditStore(file, line -> {
            try { return Instant.parse(line.split("\\|", 2)[0]); }
            catch (RuntimeException e) { return null; }
        });
    }

    private static String row(Instant time, String name) { return time + "|" + name + "\n"; }
    private static void check(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }

    private static void expiry(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path file=dir.resolve("commands.jsonl");
        Instant cutoff=NOW.minus(Duration.ofDays(30));
        Files.writeString(file,row(cutoff.minusSeconds(1),"old")+row(cutoff,"boundary")
                +row(cutoff.plusSeconds(1),"keep")+"invalid\n"+row(NOW.plusSeconds(10),"future"));
        Files.setLastModifiedTime(file,FileTime.from(NOW));
        var result=store(file).maintain(NOW);
        check(result.removed()==2,"30-day timestamp boundary");
        check(result.invalid()==1,"unknown timestamps reported");
        String contents=Files.readString(file);
        check(contents.contains("keep")&&contents.contains("future")&&contents.contains("invalid"),"new and invalid entries retained");
        check(!contents.contains("old")&&!contents.contains("boundary"),"expired records gone");
        check(store(file).maintain(NOW).removed()==0,"repeat maintenance is idempotent");
        try(var paths=Files.list(dir)){check(paths.noneMatch(p->p.toString().endsWith(".tmp")),"no stale temp files");}
    }

    private static void rotation(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path file=dir.resolve("commands.jsonl"), first=dir.resolve("commands-2026-10-03.jsonl");
        Instant yesterday=NOW.minus(Duration.ofDays(1));
        Files.writeString(first,row(yesterday,"existing"));
        Files.writeString(file,row(yesterday,"rotate"));
        Files.setLastModifiedTime(file,FileTime.from(yesterday));
        store(file).append(row(NOW,"today").stripTrailing(),NOW);
        check(Files.readString(first).contains("existing"),"archive collision does not overwrite");
        check(Files.readString(dir.resolve("commands-2026-10-03-1.jsonl")).contains("rotate"),"daily archive contains previous active log");
        check(Files.readString(file).contains("today")&&!Files.readString(file).contains("rotate"),"stable active filename contains current events");
        CommandAuditStore restarted=store(file);
        restarted.append(row(NOW.plusSeconds(1),"after restart").stripTrailing(),NOW.plusSeconds(1));
        check(Files.readString(file).contains("today")&&Files.readString(file).contains("after restart"),"restart preserves active events");
    }

    private static void concurrency(Path dir) throws Exception {
        Files.createDirectories(dir);Path file=dir.resolve("commands.jsonl");
        CommandAuditStore audit=store(file);
        var executor=Executors.newFixedThreadPool(5);
        try {
            List<java.util.concurrent.Callable<Void>> jobs=new ArrayList<>();
            for(int i=0;i<100;i++){
                int id=i;
                jobs.add(()->{audit.append(row(NOW,"event-"+id).stripTrailing(),NOW);return null;});
                jobs.add(()->{audit.maintain(NOW);return null;});
            }
            for(var result:executor.invokeAll(jobs))result.get();
            try(var lines=Files.lines(file)){check(lines.count()==100,"parallel maintenance and append do not lose events");}
        }finally{executor.shutdownNow();}
    }

    private static void safety(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path outside=dir.resolve("unrelated.jsonl");String contents=row(NOW.minus(Duration.ofDays(40)),"unrelated");
        Files.writeString(outside,contents);
        Files.createSymbolicLink(dir.resolve("commands-2026-08-01.jsonl"),outside);
        Files.writeString(dir.resolve("commands-2026-08-02.jsonl"),row(NOW,"recent in old filename"));
        Files.writeString(dir.resolve("commands-2026-10-01.jsonl"),row(NOW.minus(Duration.ofDays(40)),"expired"));
        store(dir.resolve("commands.jsonl")).maintain(NOW);
        check(Files.readString(outside).equals(contents),"unrelated files and symlink targets untouched");
        check(Files.isSymbolicLink(dir.resolve("commands-2026-08-01.jsonl")),"archive symlink untouched");
        check(Files.exists(dir.resolve("commands-2026-08-02.jsonl")),"retention uses records, not misleading filename dates");
        check(!Files.exists(dir.resolve("commands-2026-10-01.jsonl")),"empty expired archive removed");
    }

    private static void noCommands(Path dir) throws Exception {
        Files.createDirectories(dir);Path file=dir.resolve("commands.jsonl");
        Files.writeString(file,row(NOW.minus(Duration.ofDays(40)),"legacy old")+row(NOW.minus(Duration.ofDays(2)),"legacy keep"));
        Files.setLastModifiedTime(file,FileTime.from(NOW.minus(Duration.ofDays(2))));
        var result=store(file).maintain(NOW);
        check(result.removed()==1,"legacy active log is pruned without new commands");
        check(Files.readString(dir.resolve("commands-2026-10-02.jsonl")).contains("legacy keep"),"recent legacy events retained in archive");
    }

    private static void failure(Path dir) throws Exception {
        Files.createDirectories(dir);Path file=dir.resolve("commands.jsonl");
        Path bad=dir.resolve("commands-2026-10-01.jsonl");
        Files.write(bad,new byte[]{(byte)0xc3,(byte)0x28});
        store(file).append(row(NOW,"still recorded").stripTrailing(),NOW);
        check(Files.readString(file).contains("still recorded"),"cleanup failure does not prevent new audit event");
        check(Files.size(bad)==2,"failed rewrite preserves original archive");
    }
}

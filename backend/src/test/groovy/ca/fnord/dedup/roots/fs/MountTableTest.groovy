package ca.fnord.dedup.roots.fs

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class MountTableTest {
    @Test void evaluatesMountIdsAndIncludedSubmounts() {
        def table = MountTable.parse([
            '10 1 8:1 / /sources/a ro,relatime - ext4 /dev/a rw',
            '11 10 8:2 / /sources/a/nested rw,relatime - ext4 /dev/b rw',
            '12 1 8:3 / /sources/a\\040b ro,relatime - ext4 /dev/c rw',
            '13 1 8:4 / /sources/ab rw,relatime - ext4 /dev/d rw'])
        table.requireReadOnly(10,'/sources/a',false)
        assertEquals('WRITABLE_SUBMOUNT',assertThrows(SourceAccessException) { table.requireReadOnly(10,'/sources/a',true) }.code)
        assertEquals('WRITABLE_SOURCE',assertThrows(SourceAccessException) { table.requireReadOnly(11,'/sources/a/nested',false) }.code)
        assertEquals(1,table.beneath('/sources/a').size())
        assertEquals('/sources/a b',table.byId(12).path)
        assertThrows(SourceAccessException) { table.byId(99) }
    }
    @Test void kernelMountTableParses() { assertFalse(MountTable.current().mounts.empty) }
    @Test void bindAliasesResolveToBackingSubtrees() {
        def table = MountTable.parse(['10 1 8:1 /archive /sources/a ro - ext4 /dev/a rw',
            '11 1 8:1 /archive/sub /sources/b ro - ext4 /dev/a rw'])
        assertEquals('/archive',table.backingPath(10,'/sources/a'))
        assertEquals('/archive/sub/nested',table.backingPath(11,'/sources/b/nested'))
        assertTrue(MountTable.isWithin(table.backingPath(11,'/sources/b'),table.backingPath(10,'/sources/a')))
    }
}

#!/system/bin/sh
# Synthetic COW experiment. Every path and mount is temporary; never targets QQ.
set -eu

if [ "$(id -u)" != 0 ]; then
    printf '%s\n' 'This synthetic probe requires root.' >&2
    exit 2
fi

probe_dir=$(mktemp -d /data/local/tmp/qalbum-overlay-probe.XXXXXX)
mounted=0
volume_mounted=0
view_mounted=0
view_dir=""
cleanup() {
    if [ "$view_mounted" = 1 ]; then
        if ! umount "$view_dir"; then
            printf 'View unmount failed; retained own test directories.\n' >&2
            return 1
        fi
        view_mounted=0
    fi
    if [ "$mounted" = 1 ]; then
        if ! umount "$probe_dir/merged"; then
            printf 'Unmount failed; retained own test directory: %s\n' "$probe_dir" >&2
            return 1
        fi
        mounted=0
    fi
    if [ "$volume_mounted" = 1 ]; then
        if ! umount "$probe_dir/volume"; then
            printf 'Volume unmount failed; retained own test directory: %s\n' "$probe_dir" >&2
            return 1
        fi
        volume_mounted=0
    fi
    case "$probe_dir" in
        /data/local/tmp/qalbum-overlay-probe.*) rm -r -- "$probe_dir" ;;
        *) return 1 ;;
    esac
    if [ -n "$view_dir" ]; then
        case "$view_dir" in
            /data/media/0/Download/.qalbum-overlay-view.*) rmdir "$view_dir" ;;
            *) return 1 ;;
        esac
    fi
}
trap cleanup EXIT

# A dedicated filesystem avoids the casefold-capable /data mount restriction.
mkdir "$probe_dir/volume" "$probe_dir/merged"
truncate -s 32M "$probe_dir/volume.img"
mke2fs -q -t ext4 -I 256 -O ^casefold "$probe_dir/volume.img"
mount -t ext4 -o loop "$probe_dir/volume.img" "$probe_dir/volume"
volume_mounted=1
volume_dir="$probe_dir/volume"
mkdir -p "$volume_dir/lower/chatraw" "$volume_dir/lower/chatimg" "$volume_dir/lower/chatthumb"
mkdir "$volume_dir/upper" "$volume_dir/work"
printf '%s' 'synthetic-original-image-payload' > "$volume_dir/lower/chatraw/Cache_source"
ln "$volume_dir/lower/chatraw/Cache_source" "$volume_dir/lower/chatimg/Cache_alias"
printf '%s' 'synthetic-thumbnail' > "$volume_dir/lower/chatthumb/Cache_thumb"
original_hash=$(sha256sum "$volume_dir/lower/chatraw/Cache_source")
original_hash=${original_hash%% *}
chcon -R u:object_r:media_rw_data_file:s0 "$volume_dir/lower" "$volume_dir/upper" "$volume_dir/work"

mount -t overlay overlay -o "lowerdir=$volume_dir/lower,upperdir=$volume_dir/upper,workdir=$volume_dir/work,index=off,nfs_export=off,metacopy=off,redirect_dir=nofollow" "$probe_dir/merged"
mounted=1

view_dir=$(mktemp -d /data/media/0/Download/.qalbum-overlay-view.XXXXXX)
chmod 755 "$view_dir"
mount --bind "$probe_dir/merged" "$view_dir"
view_mounted=1
emulated_dir="/storage/emulated/0/Download/${view_dir##*/}"
view_visible=0
if cmp -s "$emulated_dir/chatraw/Cache_source" "$volume_dir/lower/chatraw/Cache_source"; then
    view_visible=1
    printf '%s\n' 'root_emulated_view_visible=true'
else
    printf '%s\n' 'root_emulated_view_visible=false'
    ls -ld "$view_dir" "$emulated_dir" >&2 || true
fi

cmp "$probe_dir/merged/chatraw/Cache_source" "$probe_dir/merged/chatimg/Cache_alias"
printf '%s\n' 'paths_initially_present_and_equal=true'

printf '%s' 'synthetic-qq-replacement' > "$probe_dir/merged/chatimg/Cache_alias"
raw_hash=$(sha256sum "$probe_dir/merged/chatraw/Cache_source")
raw_hash=${raw_hash%% *}
[ "$raw_hash" = "$original_hash" ]
if cmp -s "$probe_dir/merged/chatraw/Cache_source" "$probe_dir/merged/chatimg/Cache_alias"; then
    printf '%s\n' 'Write unexpectedly changed the original.' >&2
    exit 1
fi
printf '%s\n' 'write_to_img_did_not_change_raw=true'
if [ "$view_visible" = 1 ]; then
    cmp "$emulated_dir/chatraw/Cache_source" "$volume_dir/lower/chatraw/Cache_source"
    cmp "$emulated_dir/chatimg/Cache_alias" "$probe_dir/merged/chatimg/Cache_alias"
    printf '%s\n' 'root_emulated_view_reflects_isolated_write=true'
fi

lower_hash=$(sha256sum "$volume_dir/lower/chatraw/Cache_source")
lower_hash=${lower_hash%% *}
[ "$lower_hash" = "$original_hash" ]
cmp "$volume_dir/lower/chatraw/Cache_source" "$volume_dir/lower/chatimg/Cache_alias"
printf '%s\n' 'lower_original_and_alias_unchanged=true'

mv "$probe_dir/merged/chatimg/Cache_alias" "$probe_dir/merged/chatimg/Cache_renamed"
[ -f "$probe_dir/merged/chatraw/Cache_source" ]
rm "$probe_dir/merged/chatimg/Cache_renamed"
[ -f "$probe_dir/merged/chatraw/Cache_source" ]
[ -f "$probe_dir/merged/chatthumb/Cache_thumb" ]
printf '%s\n' 'rename_and_unlink_kept_raw_and_thumb=true'

rm "$probe_dir/merged/chatraw/Cache_source"
[ ! -e "$probe_dir/merged/chatraw/Cache_source" ]
[ -f "$volume_dir/lower/chatraw/Cache_source" ]
lower_hash=$(sha256sum "$volume_dir/lower/chatraw/Cache_source")
lower_hash=${lower_hash%% *}
[ "$lower_hash" = "$original_hash" ]
printf '%s\n' 'unlink_in_view_kept_lower_original=true'

cleanup
trap - EXIT
printf '%s\n' 'temporary_mount_and_files_removed=true'

# -e  Exit immediately if a command exits with a non-zero status.
#set -e


if [ $# -eq 0 ]; then
    echo "No arguments supplied, auto-detecting version"

    oldver=`grep -oP "(version\s+=\s+'\K[^'\"]+)" build.gradle`
    echo "Found version in build.gradle file: $oldver"
    if [[ $oldver =~ ^[0-9]+\.[0-9]+.*$ ]]; then
        echo "[$oldver] looks like a version number - OK"
    else
        echo "[$oldver] doesn't look like a version number - Exiting"
        exit 1
    fi
    prefix=`echo $oldver | grep -oP "(.*?)(?=[0-9]+$)"`
    build=`echo $oldver | grep -oP "(.*?\K[0-9]+$)"`
    echo "Prefix: $prefix, Build: $build"
    buildinc=$((build+1))
    echo "Incremented build number: $buildinc"
    ver="${prefix}${buildinc}"

else
    ver=$1
    if [[ $ver =~ ^[0-9]+\.[0-9]+.*$ ]]; then
        echo "1st arg [$ver] looks like a version number - OK"
    else
        echo "1st arg [$ver] doesn't look like a version number - Exiting"
        exit 1
    fi
fi

echo ""
echo "New version number: $ver"
echo ""

read -p "Version looks ok? (Y/y for yes, anything else to cancel) " -n 1 -r
echo    # (optional) move to a new line
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Not doing anything, exiting"
    exit 1
fi


bundleDir="src/com/dmtavt/fragpipe"
bundleFn="Bundle.properties"
bundleFn2="Bundle2.properties"

#sed -r -i "s/^(version[[:blank:]]*=[[:blank:]]*)'(.+?)'/\1'$ver'/g" build.gradle > build.gradle2
#sed -r -i "s/^msfragger\.gui\.version=(.+?)/\1'$ver'/g" $bundleDir/$bundleFn > $bundleDir/$bundleFn2

sed -r -i "s/^(version[[:blank:]]*=[[:blank:]]*)'(.+?)'/\1'$ver'/g" build.gradle
sed -r -i "s/^(msfragger\.gui\.version=)(.+?)/\1$ver/g" $bundleDir/$bundleFn

echo "Committing version bump to git"
git aacm "Bump to $ver"

echo "============================="
echo ""
read -p "Tag and push the build for travis release? (Y/y for yes, anything else to cancel) " -n 1 -r
echo    # (optional) move to a new line
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Not tagging/pushing, exiting"
    exit 1
fi

echo "Provide a short description message for 'git tag -m'"
read desc

git tag $ver -m "$ver $desc"
git push origin $ver

cd ..
set  "tag_name=v2.2.0"


git tag -d %tag_name%

git push origin --delete %tag_name%

git tag %tag_name%

git push origin %tag_name%



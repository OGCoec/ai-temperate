param(
    [Parameter(Mandatory = $true)]
    [string] $ShoppingRoot,

    [string] $AiTemperateRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

# 归一化只忽略两个项目必然不同的包名、项目内 import 和平台换行符，其他代码差异必须失败。
function Get-NormalizedJavaSource {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [Parameter(Mandatory = $true)]
        [string] $PackagePattern,

        [string] $ProjectImportPattern = ''
    )

    $source = Get-Content -LiteralPath $Path -Raw -Encoding utf8
    $source = $source -replace $PackagePattern, 'package normalized;'
    if ($ProjectImportPattern) {
        $source = $source -replace $ProjectImportPattern, 'import normalized.OutboundRouteResolver;'
    }
    return $source -replace "`r`n", "`n"
}

$aiAliyunUtils = Join-Path $AiTemperateRoot `
    'ai-temperate-common/src/main/java/com/example/temperate/common/aliyun/AliyunUtils.java'
$shoppingAliyunUtils = Join-Path $ShoppingRoot `
    'shopping-common/src/main/java/com/example/ShoppingSystem/Utils/AliyunUtils.java'
$aiRouteResolver = Join-Path $AiTemperateRoot `
    'ai-temperate-common/src/main/java/com/example/temperate/common/proxy/OutboundRouteResolver.java'
$shoppingRouteResolver = Join-Path $ShoppingRoot `
    'shopping-common/src/main/java/com/example/ShoppingSystem/common/proxy/OutboundRouteResolver.java'

$aiAliyunSource = Get-NormalizedJavaSource `
    -Path $aiAliyunUtils `
    -PackagePattern 'package com\.example\.temperate\.common\.aliyun;' `
    -ProjectImportPattern 'import com\.example\.temperate\.common\.proxy\.OutboundRouteResolver;'
$shoppingAliyunSource = Get-NormalizedJavaSource `
    -Path $shoppingAliyunUtils `
    -PackagePattern 'package com\.example\.ShoppingSystem\.Utils;' `
    -ProjectImportPattern 'import com\.example\.ShoppingSystem\.common\.proxy\.OutboundRouteResolver;'
if ($aiAliyunSource -cne $shoppingAliyunSource) {
    throw 'AliyunUtils 源码已发生漂移。'
}

$aiResolverSource = Get-NormalizedJavaSource `
    -Path $aiRouteResolver `
    -PackagePattern 'package com\.example\.temperate\.common\.proxy;'
$shoppingResolverSource = Get-NormalizedJavaSource `
    -Path $shoppingRouteResolver `
    -PackagePattern 'package com\.example\.ShoppingSystem\.common\.proxy;'
if ($aiResolverSource -cne $shoppingResolverSource) {
    throw 'OutboundRouteResolver 源码已发生漂移。'
}

Write-Output '阿里云工具源码保持一致；shopping 的依赖管理不参与对齐。'

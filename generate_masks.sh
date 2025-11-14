#!/bin/bash

# Script pour générer les masques et images à partir des annotations QuPath

echo "🎭 Génération des masques à partir des annotations QuPath..."
echo ""

# Exécuter la tâche Gradle
gradle runGenerateMasks

echo ""
echo "Over, à vous ! "
